"""
Face Verification Microservice
===============================
Compares a captured face photo against a stored profile photo.

API:
  POST /verify-face
    Body (JSON):
      {
        "captured_photo": "<base64-encoded JPEG>",
        "reference_photo_path": "./uploads/profile-pictures/user_5_abc12345.jpg"
      }
    Response:
      {
        "match": true/false,
        "confidence": 0.85,
        "distance": 0.42,
        "message": "Face matched" / "Face not matched" / "No face detected"
      }

  GET /health
    Response: { "status": "ok" }

Run:
  pip install -r requirements.txt
  python face_service.py

Listens on port 5001.
"""

import base64
import hmac
import io
import os
import sys
import logging

from flask import Flask, request, jsonify
from flask_cors import CORS
from werkzeug.exceptions import HTTPException

import face_recognition
import numpy as np
from PIL import Image, ImageOps

# ============================================================
# CONFIG
# ============================================================
FACE_MATCH_THRESHOLD = 0.60  # max distance for a match (lower = stricter)
PORT = int(os.environ.get("PORT", 5001))

MIN_IMAGE_DIMENSION = 50
MAX_IMAGE_DIMENSION = 4096
MAX_BASE64_STRING_LENGTH = 15 * 1024 * 1024  # ~11.25 MB raw data ceiling

# Shared secret between Spring Boot backend and this microservice
INTERNAL_SERVICE_SECRET = os.environ.get("INTERNAL_SERVICE_SECRET")
if not INTERNAL_SERVICE_SECRET:
    if os.environ.get("FLASK_ENV") == "production":
        raise RuntimeError("CRITICAL: INTERNAL_SERVICE_SECRET environment variable is mandatory in production!")
    log.warning("WARNING: INTERNAL_SERVICE_SECRET is not configured. Service will reject authenticated verification requests until configured.")

# Reference photos must live inside the uploads directory only.
# Robustly anchor to project root or current working dir.
SERVICE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SERVICE_DIR, ".."))
ALLOWED_BASE_DIRS = [
    os.path.abspath(os.path.join(PROJECT_ROOT, "uploads", "profile-pictures")),
    os.path.abspath(os.path.join(PROJECT_ROOT, "facultybackend", "uploads", "profile-pictures")),
    os.path.abspath(os.path.join(SERVICE_DIR, "..", "facultybackend", "uploads", "profile-pictures")),
    os.path.abspath(os.path.join(os.getcwd(), "uploads", "profile-pictures")),
    os.path.abspath(os.path.join(os.getcwd(), "facultybackend", "uploads", "profile-pictures")),
    os.path.abspath("C:/FacultyAttendance/uploads/profile-pictures"),
]
if os.environ.get("UPLOAD_DIR"):
    ALLOWED_BASE_DIRS.append(os.path.abspath(os.environ.get("UPLOAD_DIR")))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("face_service")

app = Flask(__name__)
# 10 MB payload limit to prevent unbounded memory consumption (DoS)
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024

# Restrict CORS to local backend calls only
CORS(app, origins=["http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:8081", "http://127.0.0.1:8081"])


@app.errorhandler(413)
def request_entity_too_large(error):
    """Return JSON response when incoming payload exceeds MAX_CONTENT_LENGTH."""
    return jsonify({"error": "Payload too large. Maximum image size is 10 MB."}), 413


# ============================================================
# HELPERS
# ============================================================

def decode_base64_image(b64_string: str) -> np.ndarray:
    """Decode a base64 string (with or without data-URI prefix) to a numpy array."""
    if not b64_string:
        raise ValueError("Empty base64 string provided")
    if len(b64_string) > MAX_BASE64_STRING_LENGTH:
        raise ValueError(f"Base64 image string exceeds maximum allowed length of {MAX_BASE64_STRING_LENGTH} characters")
    if "," in b64_string:
        b64_string = b64_string.split(",", 1)[1]
    image_bytes = base64.b64decode(b64_string.strip())
    image = Image.open(io.BytesIO(image_bytes))
    image = ImageOps.exif_transpose(image).convert("RGB")
    width, height = image.size
    if width < MIN_IMAGE_DIMENSION or height < MIN_IMAGE_DIMENSION:
        raise ValueError(f"Image dimensions ({width}x{height}) are too small. Minimum dimension is {MIN_IMAGE_DIMENSION}px.")
    if width > MAX_IMAGE_DIMENSION or height > MAX_IMAGE_DIMENSION:
        raise ValueError(f"Image dimensions ({width}x{height}) exceed maximum allowed dimension of {MAX_IMAGE_DIMENSION}px.")
    return np.array(image)


def load_image_from_path(path: str) -> np.ndarray:
    """Load an image from disk and return as numpy array with correct EXIF orientation."""
    image = Image.open(path)
    image = ImageOps.exif_transpose(image).convert("RGB")
    width, height = image.size
    if width < MIN_IMAGE_DIMENSION or height < MIN_IMAGE_DIMENSION:
        raise ValueError(f"Image dimensions ({width}x{height}) are too small. Minimum dimension is {MIN_IMAGE_DIMENSION}px.")
    if width > MAX_IMAGE_DIMENSION or height > MAX_IMAGE_DIMENSION:
        raise ValueError(f"Image dimensions ({width}x{height}) exceed maximum allowed dimension of {MAX_IMAGE_DIMENSION}px.")
    return np.array(image)


def evaluate_image_lighting(image_array: np.ndarray) -> str:
    """
    Evaluate lighting condition of an RGB image array using standard perceptual luminance:
    Y = 0.299 * R + 0.587 * G + 0.114 * B
    Returns 'low_light', 'overexposed', or 'normal'.
    """
    if image_array is None or image_array.size == 0:
        return "normal"
    if len(image_array.shape) == 3 and image_array.shape[2] >= 3:
        luminance = 0.299 * image_array[:, :, 0] + 0.587 * image_array[:, :, 1] + 0.114 * image_array[:, :, 2]
    else:
        luminance = image_array.astype(float)
    mean_luminance = float(np.mean(luminance))
    if mean_luminance < 40.0:
        return "low_light"
    elif mean_luminance > 245.0:
        return "overexposed"
    return "normal"


def is_path_within_allowed_dir(path: str) -> bool:
    """True only if the absolute path resolves inside an allowed uploads directory (case-insensitive on Windows)."""
    if not path:
        return False
    target_abs = os.path.abspath(os.path.join(os.getcwd(), path)) if not os.path.isabs(path) else os.path.abspath(path)
    target = os.path.normcase(target_abs)
    for allowed_dir in ALLOWED_BASE_DIRS:
        norm_allowed = os.path.normcase(os.path.abspath(allowed_dir))
        if target == norm_allowed or target.startswith(norm_allowed + os.sep):
            return True
    return False


def resolve_reference_path(path: str) -> str:
    """Resolve reference photo path to an existing file inside allowed directories."""
    if not path:
        return ""
    candidates = []
    # Candidate 1: Direct absolute or current-working-dir relative path
    direct = path if os.path.isabs(path) else os.path.abspath(os.path.join(os.getcwd(), path))
    candidates.append(direct)

    # Candidate 2: Relative to PROJECT_ROOT or facultybackend
    cleaned = path.replace("\\", "/").lstrip(".").lstrip("/")
    candidates.append(os.path.abspath(os.path.join(PROJECT_ROOT, cleaned)))
    candidates.append(os.path.abspath(os.path.join(PROJECT_ROOT, "facultybackend", cleaned)))

    # Candidate 3: In each allowed directory by filename
    filename = os.path.basename(path)
    for allowed_dir in ALLOWED_BASE_DIRS:
        candidates.append(os.path.abspath(os.path.join(allowed_dir, filename)))

    for cand in candidates:
        if os.path.isfile(cand) and is_path_within_allowed_dir(cand):
            return cand

    return direct



def get_face_encodings(image_array: np.ndarray):
    """Extract face encodings from an image. Returns list of 128-d vectors."""
    pil_img = Image.fromarray(image_array)
    max_size = 1200
    if max(pil_img.size) > max_size:
        ratio = max_size / max(pil_img.size)
        new_size = (int(pil_img.size[0] * ratio), int(pil_img.size[1] * ratio))
        pil_img = pil_img.resize(new_size, Image.LANCZOS)
        image_array = np.array(pil_img)

    face_locations = face_recognition.face_locations(image_array, number_of_times_to_upsample=1, model="hog")
    if not face_locations:
        face_locations = face_recognition.face_locations(image_array, number_of_times_to_upsample=2, model="hog")
    if not face_locations:
        return []

    encodings = face_recognition.face_encodings(image_array, known_face_locations=face_locations)
    return encodings


def compare_faces(captured_encoding, reference_encoding):
    """Compare two face encodings. Returns (is_match, distance)."""
    distance = float(face_recognition.face_distance([reference_encoding], captured_encoding)[0])
    is_match = distance <= FACE_MATCH_THRESHOLD
    return is_match, distance


# ============================================================
# ROUTES
# ============================================================

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "service": "face-verification",
        "version": "1.0.0",
        "threshold": FACE_MATCH_THRESHOLD,
        "max_payload_mb": 10,
    })


@app.route("/verify-face", methods=["POST"])
def verify_face():
    """
    Verify that a captured photo matches a reference profile photo.

    Request JSON:
      - captured_photo: base64-encoded image (JPEG/PNG)
      - reference_photo_path: file path to the stored profile photo
    """
    # Enforce internal service secret authentication
    auth_header = request.headers.get("X-Internal-Secret")
    if not INTERNAL_SERVICE_SECRET or not auth_header or not hmac.compare_digest(auth_header, INTERNAL_SERVICE_SECRET):
        log.warning("Unauthorized access attempt to /verify-face (missing, unconfigured, or invalid secret header)")
        return jsonify({"error": "Unauthorized"}), 401

    try:
        data = request.get_json()

        if not data:
            return jsonify({"error": "No JSON body provided"}), 400

        captured_b64 = data.get("captured_photo")
        reference_path = data.get("reference_photo_path")

        if not captured_b64:
            return jsonify({"error": "captured_photo is required"}), 400
        if not reference_path:
            return jsonify({"error": "reference_photo_path is required"}), 400

        # Resolve reference path and enforce directory containment
        resolved_path = resolve_reference_path(reference_path)
        if not is_path_within_allowed_dir(resolved_path):
            log.warning("Blocked reference path outside the uploads directory: %s", reference_path)
            return jsonify({
                "error": "Unauthorized file path access"
            }), 403

        # Check reference file exists
        if not os.path.exists(resolved_path):
            log.warning("Reference photo not found: %s (resolved: %s)", reference_path, resolved_path)
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": "Reference photo not found. Please update your profile photo."
            })

        # Decode captured photo
        log.info("Decoding captured photo...")
        try:
            captured_img = decode_base64_image(captured_b64)
        except Exception as e:
            log.warning("Failed to decode captured photo: %s", str(e))
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": "Invalid or unreadable camera photo. Please try capturing your face again."
            })

        # Load reference photo
        log.info("Loading reference photo: %s", resolved_path)
        try:
            reference_img = load_image_from_path(resolved_path)
        except Exception as e:
            log.warning("Failed to read reference photo from %s: %s", resolved_path, str(e))
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": "Registered profile photo is unreadable or corrupted. Please update your profile photo."
            })

        # Get face encodings
        log.info("Extracting face encodings from captured photo...")
        captured_encodings = get_face_encodings(captured_img)
        if not captured_encodings:
            log.warning("No face detected in captured photo")
            lighting = evaluate_image_lighting(captured_img)
            if lighting == "low_light":
                msg = "No face detected. Low lighting detected — please face a light source and retry."
            elif lighting == "overexposed":
                msg = "No face detected. Frame is overexposed — please avoid direct backlight and retry."
            else:
                msg = "No face detected in captured photo. Please try again with your face clearly visible."
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": msg
            })

        if len(captured_encodings) > 1:
            log.warning("Multiple faces (%d) detected in captured camera frame", len(captured_encodings))
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": f"Multiple faces ({len(captured_encodings)}) detected in camera frame. Please ensure only you are visible."
            })

        log.info("Extracting face encodings from reference photo...")
        reference_encodings = get_face_encodings(reference_img)
        if not reference_encodings:
            log.warning("No face detected in reference photo")
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": "No face detected in your profile photo. Please update your profile photo first."
            })

        if len(reference_encodings) > 1:
            log.warning("Multiple faces (%d) detected in reference profile photo", len(reference_encodings))
            return jsonify({
                "match": False,
                "confidence": 0,
                "distance": 1.0,
                "message": f"Multiple faces ({len(reference_encodings)}) detected in registered profile photo. Please update your profile photo."
            })

        # Compare single captured face against single reference face
        is_match, distance = compare_faces(captured_encodings[0], reference_encodings[0])
        confidence = max(0, round((1 - distance) * 100, 1))
        log.info(
            "Verification result: match=%s, distance=%.4f, confidence=%.1f%%",
            is_match, distance, confidence
        )

        if is_match:
            return jsonify({
                "match": True,
                "confidence": confidence,
                "distance": round(distance, 4),
                "message": "Face verified successfully"
            })
        else:
            return jsonify({
                "match": False,
                "confidence": confidence,
                "distance": round(distance, 4),
                "message": "Face does not match your profile photo. Please try again."
            })

    except HTTPException:
        raise
    except Exception as e:
        log.error("Face verification error: %s", str(e), exc_info=True)
        return jsonify({
            "error": "Face verification processing failed. Please try again.",
            "match": False,
            "confidence": 0,
            "distance": 1.0,
        }), 500


@app.route("/detect-face", methods=["POST"])
def detect_face():
    """
    Validate that a captured image contains a valid face (used during registration).

    Request JSON:
      - captured_photo: base64-encoded image OR
      - image_path: path to image inside uploads directory
    """
    auth_header = request.headers.get("X-Internal-Secret")
    if not INTERNAL_SERVICE_SECRET or not auth_header or not hmac.compare_digest(auth_header, INTERNAL_SERVICE_SECRET):
        log.warning("Unauthorized access attempt to /detect-face")
        return jsonify({"error": "Unauthorized"}), 401

    try:
        data = request.get_json()
        if not data:
            return jsonify({"error": "No JSON body provided"}), 400

        captured_b64 = data.get("captured_photo")
        image_path = data.get("image_path")

        if not captured_b64 and not image_path:
            return jsonify({"error": "captured_photo or image_path is required"}), 400

        try:
            if image_path:
                resolved_image_path = resolve_reference_path(image_path)
                if not is_path_within_allowed_dir(resolved_image_path):
                    return jsonify({"error": "Unauthorized file path access"}), 403
                if not os.path.exists(resolved_image_path):
                    return jsonify({"valid": False, "faces_detected": 0, "message": "Image not found"}), 404
                img = load_image_from_path(resolved_image_path)
            else:
                img = decode_base64_image(captured_b64)
        except Exception as e:
            log.warning("Image decoding failed in /detect-face: %s", str(e))
            return jsonify({
                "valid": False,
                "faces_detected": 0,
                "message": "Image file is unreadable or corrupted. Please provide a valid JPEG or PNG image."
            })

        encodings = get_face_encodings(img)
        face_count = len(encodings)

        if face_count == 0:
            lighting = evaluate_image_lighting(img)
            if lighting == "low_light":
                msg = "No face detected. Low lighting detected — please ensure face is well-lit and retry."
            elif lighting == "overexposed":
                msg = "No face detected. Frame is overexposed — please avoid direct backlight and retry."
            else:
                msg = "No face detected in the photo. Please ensure face is clearly visible and well-lit."
            return jsonify({
                "valid": False,
                "faces_detected": 0,
                "message": msg
            })
        elif face_count == 1:
            return jsonify({
                "valid": True,
                "faces_detected": 1,
                "message": "Face detected and validated successfully."
            })
        else:
            return jsonify({
                "valid": False,
                "faces_detected": face_count,
                "message": f"Multiple faces ({face_count}) detected. Please ensure only the individual is in frame."
            })

    except HTTPException:
        raise
    except Exception as e:
        log.error("Face detection error: %s", str(e), exc_info=True)
        return jsonify({
            "valid": False,
            "faces_detected": 0,
            "error": "Face detection failed: " + str(e)
        }), 500


# ============================================================
# MAIN
# ============================================================

if __name__ == "__main__":
    log.info("Starting Face Verification Service on port %d...", PORT)
    log.info("Face match threshold: distance <= %.2f", FACE_MATCH_THRESHOLD)
    # 127.0.0.1: only the local Spring Boot backend is supposed to call this
    # service; never expose it on all interfaces.
    app.run(host="0.0.0.0", port=PORT, debug=False)
