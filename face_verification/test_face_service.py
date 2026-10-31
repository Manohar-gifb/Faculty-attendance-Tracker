import unittest
from unittest.mock import patch
import base64
import io
import os
import sys
import tempfile

# Ensure face_verification dir is on sys.path and provide test internal secret
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("INTERNAL_SERVICE_SECRET", "test_internal_service_secret_key_face_2026")

from PIL import Image
import numpy as np
from face_service import (
    app,
    INTERNAL_SERVICE_SECRET,
    ALLOWED_BASE_DIRS,
    resolve_reference_path,
    is_path_within_allowed_dir,
    decode_base64_image,
    evaluate_image_lighting,
    MIN_IMAGE_DIMENSION,
    MAX_IMAGE_DIMENSION,
    MAX_BASE64_STRING_LENGTH,
)


class FaceServiceTestCase(unittest.TestCase):

    def setUp(self):
        app.config["TESTING"] = True
        self.client = app.test_client()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.test_upload_dir = os.path.abspath(self.temp_dir.name)
        ALLOWED_BASE_DIRS.append(self.test_upload_dir)

    def tearDown(self):
        if self.test_upload_dir in ALLOWED_BASE_DIRS:
            ALLOWED_BASE_DIRS.remove(self.test_upload_dir)
        self.temp_dir.cleanup()

    def _create_test_image_b64(self, width=200, height=200, color="gray", fmt="JPEG"):
        img = Image.new("RGB", (width, height), color=color)
        buf = io.BytesIO()
        img.save(buf, format=fmt)
        return base64.b64encode(buf.getvalue()).decode("utf-8")

    def _save_test_image_file(self, filename, width=200, height=200, color="gray", fmt="JPEG"):
        img = Image.new("RGB", (width, height), color=color)
        path = os.path.join(self.test_upload_dir, filename)
        img.save(path, format=fmt)
        return path

    def test_health_endpoint(self):
        res = self.client.get("/health")
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertEqual(data["status"], "ok")
        self.assertEqual(data["service"], "face-verification")
        self.assertEqual(data["version"], "1.0.0")
        self.assertEqual(data["threshold"], 0.60)
        self.assertEqual(data["max_payload_mb"], 10)

    def test_auth_rejection_without_secret(self):
        res = self.client.post("/verify-face", json={"captured_photo": "abc", "reference_photo_path": "xyz"})
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.get_json()["error"], "Unauthorized")

    def test_auth_rejection_with_invalid_secret(self):
        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": "wrong_secret"},
            json={"captured_photo": "abc", "reference_photo_path": "xyz"}
        )
        self.assertEqual(res.status_code, 401)

    def test_auth_rejection_with_empty_secret(self):
        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": ""},
            json={"captured_photo": "abc", "reference_photo_path": "xyz"}
        )
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.get_json()["error"], "Unauthorized")

    def test_detect_face_rejection_without_secret(self):
        res = self.client.post("/detect-face", json={"captured_photo": "abc"})
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.get_json()["error"], "Unauthorized")

    def test_detect_face_rejection_with_invalid_secret(self):
        res = self.client.post(
            "/detect-face",
            headers={"X-Internal-Secret": "wrong_secret"},
            json={"captured_photo": "abc"}
        )
        self.assertEqual(res.status_code, 401)

    def test_detect_face_rejection_with_empty_secret(self):
        res = self.client.post(
            "/detect-face",
            headers={"X-Internal-Secret": ""},
            json={"captured_photo": "abc"}
        )
        self.assertEqual(res.status_code, 401)

    def test_verify_face_missing_body(self):
        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={}
        )
        self.assertEqual(res.status_code, 400)

    def test_path_containment_prevents_directory_traversal(self):
        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": "dummy",
                "reference_photo_path": "../../../windows/system32/cmd.exe"
            }
        )
        self.assertEqual(res.status_code, 403)
        self.assertIn("Unauthorized file path access", res.get_json()["error"])

    def test_nonexistent_reference_photo(self):
        missing_ref = os.path.join(self.test_upload_dir, "non_existent_9999.jpg")
        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": "dummy",
                "reference_photo_path": missing_ref
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("Reference photo not found", data["message"])

    def test_unreadable_reference_photo(self):
        b64_captured = self._create_test_image_b64(width=200, height=200)
        corrupt_file = os.path.join(self.test_upload_dir, "test_corrupt_temp.jpg")
        with open(corrupt_file, "wb") as f:
            f.write(b"\xff\xd8\xff\xe0" + b"\x00" * 50)

        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": b64_captured,
                "reference_photo_path": corrupt_file
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("unreadable or corrupted", data["message"])

    def test_no_face_detected_in_normal_image(self):
        # Medium gray (luminance around 128) - not low_light, not overexposed
        b64_captured = self._create_test_image_b64(width=200, height=200, color=(128, 128, 128))
        ref_file = self._save_test_image_file("test_blank_ref.jpg", width=200, height=200, color=(128, 128, 128))

        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": b64_captured,
                "reference_photo_path": ref_file
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("No face detected in captured photo", data["message"])

    def test_low_light_diagnostic_in_verify_face(self):
        # Very dark image (mean luminance < 40)
        b64_captured = self._create_test_image_b64(width=200, height=200, color=(15, 15, 15))
        ref_file = self._save_test_image_file("test_ref_lowlight.jpg", width=200, height=200)

        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": b64_captured,
                "reference_photo_path": ref_file
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("Low lighting detected", data["message"])

    def test_overexposed_diagnostic_in_verify_face(self):
        # Very bright image (mean luminance > 245)
        b64_captured = self._create_test_image_b64(width=200, height=200, color=(255, 255, 255))
        ref_file = self._save_test_image_file("test_ref_overexposed.jpg", width=200, height=200)

        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": b64_captured,
                "reference_photo_path": ref_file
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("Frame is overexposed", data["message"])

    def test_multiple_faces_in_captured_frame_rejected(self):
        b64_captured = self._create_test_image_b64(width=200, height=200)
        ref_file = self._save_test_image_file("test_ref_multi.jpg", width=200, height=200)

        dummy_enc1 = np.zeros(128)
        dummy_enc2 = np.ones(128)

        with patch("face_service.get_face_encodings") as mock_get_enc:
            # First call for captured photo returns 2 encodings
            mock_get_enc.return_value = [dummy_enc1, dummy_enc2]

            res = self.client.post(
                "/verify-face",
                headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
                json={
                    "captured_photo": b64_captured,
                    "reference_photo_path": ref_file
                }
            )
            self.assertEqual(res.status_code, 200)
            data = res.get_json()
            self.assertFalse(data["match"])
            self.assertEqual(data["confidence"], 0)
            self.assertEqual(data["distance"], 1.0)
            self.assertIn("Multiple faces (2) detected in camera frame", data["message"])

    def test_multiple_faces_in_reference_photo_rejected(self):
        b64_captured = self._create_test_image_b64(width=200, height=200)
        ref_file = self._save_test_image_file("test_ref_multi2.jpg", width=200, height=200)

        dummy_enc1 = np.zeros(128)
        dummy_enc2 = np.ones(128)

        with patch("face_service.get_face_encodings") as mock_get_enc:
            # Call 1: captured has 1 face. Call 2: reference has 2 faces.
            mock_get_enc.side_effect = [[dummy_enc1], [dummy_enc1, dummy_enc2]]

            res = self.client.post(
                "/verify-face",
                headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
                json={
                    "captured_photo": b64_captured,
                    "reference_photo_path": ref_file
                }
            )
            self.assertEqual(res.status_code, 200)
            data = res.get_json()
            self.assertFalse(data["match"])
            self.assertEqual(data["confidence"], 0)
            self.assertEqual(data["distance"], 1.0)
            self.assertIn("Multiple faces (2) detected in registered profile photo", data["message"])

    def test_single_face_match_success(self):
        b64_captured = self._create_test_image_b64(width=200, height=200)
        ref_file = self._save_test_image_file("test_ref_match.jpg", width=200, height=200)

        dummy_enc1 = np.zeros(128)
        dummy_enc2 = np.zeros(128)

        with patch("face_service.get_face_encodings") as mock_get_enc, \
             patch("face_service.compare_faces", return_value=(True, 0.25)):
            mock_get_enc.side_effect = [[dummy_enc1], [dummy_enc2]]

            res = self.client.post(
                "/verify-face",
                headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
                json={
                    "captured_photo": b64_captured,
                    "reference_photo_path": ref_file
                }
            )
            self.assertEqual(res.status_code, 200)
            data = res.get_json()
            self.assertTrue(data["match"])
            self.assertEqual(data["distance"], 0.25)
            self.assertEqual(data["message"], "Face verified successfully")

    def test_413_payload_too_large_json_response(self):
        # Flask MAX_CONTENT_LENGTH error handler returns JSON 413
        oversized_data = b"x" * (11 * 1024 * 1024)
        res = self.client.post(
            "/verify-face",
            headers={
                "X-Internal-Secret": INTERNAL_SERVICE_SECRET,
                "Content-Type": "application/json"
            },
            data=oversized_data
        )
        self.assertEqual(res.status_code, 413)
        data = res.get_json()
        self.assertIn("Payload too large", data["error"])

    def test_image_dimensions_below_minimum_rejected(self):
        # 30x30 image is below MIN_IMAGE_DIMENSION (50px)
        b64_too_small = self._create_test_image_b64(width=30, height=30)
        ref_file = self._save_test_image_file("ref_normal.jpg", width=200, height=200)

        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": b64_too_small,
                "reference_photo_path": ref_file
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("Invalid or unreadable camera photo", data["message"])

    def test_malformed_base64_data_rejected(self):
        ref_file = self._save_test_image_file("ref_normal.jpg", width=200, height=200)
        res = self.client.post(
            "/verify-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={
                "captured_photo": "not_valid_base64_!@#$%",
                "reference_photo_path": ref_file
            }
        )
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertFalse(data["match"])
        self.assertIn("Invalid or unreadable camera photo", data["message"])

    def test_detect_face_diagnostics(self):
        # Dark image -> Low lighting detected
        b64_dark = self._create_test_image_b64(width=200, height=200, color=(10, 10, 10))
        res_dark = self.client.post(
            "/detect-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={"captured_photo": b64_dark}
        )
        self.assertEqual(res_dark.status_code, 200)
        self.assertFalse(res_dark.get_json()["valid"])
        self.assertIn("Low lighting detected", res_dark.get_json()["message"])

        # Overexposed image -> Frame is overexposed
        b64_bright = self._create_test_image_b64(width=200, height=200, color=(255, 255, 255))
        res_bright = self.client.post(
            "/detect-face",
            headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
            json={"captured_photo": b64_bright}
        )
        self.assertEqual(res_bright.status_code, 200)
        self.assertFalse(res_bright.get_json()["valid"])
        self.assertIn("Frame is overexposed", res_bright.get_json()["message"])

    def test_detect_face_multiple_faces(self):
        b64_img = self._create_test_image_b64(width=200, height=200)
        dummy_enc1 = np.zeros(128)
        dummy_enc2 = np.ones(128)

        with patch("face_service.get_face_encodings", return_value=[dummy_enc1, dummy_enc2]):
            res = self.client.post(
                "/detect-face",
                headers={"X-Internal-Secret": INTERNAL_SERVICE_SECRET},
                json={"captured_photo": b64_img}
            )
            self.assertEqual(res.status_code, 200)
            data = res.get_json()
            self.assertFalse(data["valid"])
            self.assertEqual(data["faces_detected"], 2)
            self.assertIn("Multiple faces (2) detected", data["message"])


if __name__ == "__main__":
    unittest.main()
