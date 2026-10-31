import { useRef, useState, useEffect, useCallback } from "react";
import { FaCamera, FaRedo, FaTimes, FaVideo, FaUpload } from "react-icons/fa";

/** Map a getUserMedia camera error name into an actionable diagnostic message. */
const describeCameraError = (err) => {
    switch (err && err.name) {
        case "NotAllowedError":
        case "PermissionDeniedError":
            return "Camera access denied. Please click the lock/camera icon in your browser address bar and enable camera access.";
        case "NotFoundError":
        case "DevicesNotFoundError":
            return "No camera detected on this device. Please connect a webcam or switch to a device with a camera.";
        case "NotReadableError":
        case "TrackStartError":
            return "Camera is in use by another application or tab (e.g. Zoom, Teams). Please close other apps and try again.";
        case "OverconstrainedError":
            return "Requested camera resolution is not supported by your hardware.";
        default:
            return "Camera error: " + ((err && err.message) || "Unable to access camera.");
    }
};

/**
 * WebcamCapture — captures a photo from the device camera or allows file upload.
 *
 * Props:
 *   onCapture(blob)   — called with the captured JPEG Blob or uploaded File
 *   onClear()         — called when the user removes the captured photo
 *   disabled          — disable interactions (e.g. while submitting)
 *   allowUpload       — enable file upload option alongside camera (default: false)
 */
export default function WebcamCapture({ onCapture, onClear, disabled = false, allowUpload = false }) {
    const videoRef = useRef(null);
    const canvasRef = useRef(null);
    const streamRef = useRef(null);
    const fileInputRef = useRef(null);

    const [cameraState, setCameraState] = useState("idle"); // idle | streaming | captured
    const [capturedImage, setCapturedImage] = useState(null); // data URL for preview
    const [error, setError] = useState("");

    // ---- STOP CAMERA ----
    const stopCamera = useCallback(() => {
        if (streamRef.current) {
            streamRef.current.getTracks().forEach((t) => t.stop());
            streamRef.current = null;
        }
    }, []);

    // ---- START CAMERA ----
    // Step 1: Get the stream and store it, then switch to "streaming" state
    //         so the <video> element renders.
    const startCamera = useCallback(async () => {
        if (disabled) return;
        setError("");
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                video: {
                    width: { ideal: 640 },
                    height: { ideal: 480 },
                    facingMode: "user",
                },
                audio: false,
            });
            // Store stream first, then render the video element
            streamRef.current = stream;
            setCameraState("streaming");
        } catch (err) {
            console.error("Camera error:", err);
            setError(describeCameraError(err));
        }
    }, [disabled]);

    // Step 2: When "streaming" state is active and videoRef is in the DOM,
    //         attach the stream to the <video> element.
    useEffect(() => {
        if (cameraState === "streaming" && videoRef.current && streamRef.current) {
            videoRef.current.srcObject = streamRef.current;
            videoRef.current.play().catch(() => {});
        }
    }, [cameraState]);

    // ---- CAPTURE PHOTO ----
    const capturePhoto = useCallback(() => {
        const video = videoRef.current;
        const canvas = canvasRef.current;
        if (!video || !canvas) return;
        if (!video.videoWidth || !video.videoHeight) return;

        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext("2d");

        // Mirror the image for front camera (selfie mode)
        ctx.translate(canvas.width, 0);
        ctx.scale(-1, 1);
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        ctx.setTransform(1, 0, 0, 1, 0, 0); // reset

        canvas.toBlob(
            (blob) => {
                if (blob) {
                    const url = URL.createObjectURL(blob);
                    setCapturedImage(url);
                    setCameraState("captured");
                    stopCamera();
                    if (onCapture) onCapture(blob);
                }
            },
            "image/jpeg",
            0.92
        );
    }, [stopCamera, onCapture]);

    // ---- FILE UPLOAD ----
    const handleFileUpload = (e) => {
        const file = e.target.files?.[0];
        if (!file) return;

        const allowedTypes = ["image/jpeg", "image/png", "image/webp"];
        if (!allowedTypes.includes(file.type)) {
            setError("Please upload a JPEG, PNG or WebP image.");
            if (fileInputRef.current) fileInputRef.current.value = "";
            return;
        }
        if (file.size === 0) {
            setError("Please choose a valid image file.");
            if (fileInputRef.current) fileInputRef.current.value = "";
            return;
        }
        if (file.size > 5 * 1024 * 1024) {
            setError("File size must be less than 5MB.");
            if (fileInputRef.current) fileInputRef.current.value = "";
            return;
        }

        setError("");
        if (capturedImage) URL.revokeObjectURL(capturedImage);
        const url = URL.createObjectURL(file);
        setCapturedImage(url);
        setCameraState("captured");
        stopCamera();
        if (onCapture) onCapture(file);
    };

    // ---- RETAKE ----
    const retake = useCallback(() => {
        if (capturedImage) URL.revokeObjectURL(capturedImage);
        setCapturedImage(null);
        setError("");
        setCameraState("idle");
        if (fileInputRef.current) fileInputRef.current.value = "";
        if (onClear) onClear();
        // If not upload-only, restart camera
        if (!allowUpload) {
            setTimeout(() => {
                startCamera();
            }, 150);
        }
    }, [capturedImage, onClear, startCamera, allowUpload]);

    // ---- REMOVE PHOTO ----
    const removePhoto = useCallback(() => {
        if (capturedImage) URL.revokeObjectURL(capturedImage);
        setCapturedImage(null);
        setError("");
        setCameraState("idle");
        if (fileInputRef.current) fileInputRef.current.value = "";
        stopCamera();
        if (onClear) onClear();
    }, [capturedImage, onClear, stopCamera]);

    // ---- CLEANUP ON UNMOUNT ----
    useEffect(() => {
        return () => {
            stopCamera();
            if (capturedImage) URL.revokeObjectURL(capturedImage);
        };
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div className="wc-container">
            <canvas ref={canvasRef} style={{ display: "none" }} />
            {allowUpload && (
                <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/jpeg,image/png,image/webp"
                    style={{ display: "none" }}
                    onChange={handleFileUpload}
                    disabled={disabled}
                />
            )}

            {/* IDLE STATE — show start camera and upload buttons */}
            {cameraState === "idle" && !capturedImage && (
                <div className="wc-idle">
                    <div className="wc-idle-icon">
                        <FaVideo />
                    </div>
                    <p className="wc-idle-text">
                        {allowUpload ? "Capture using camera or upload a photo" : "Capture a photo using your webcam"}
                    </p>
                    <div style={{ display: "flex", gap: "10px", justifyContent: "center", flexWrap: "wrap" }}>
                        <button
                            type="button"
                            className="wc-btn wc-btn-start"
                            onClick={startCamera}
                            disabled={disabled}
                        >
                            <FaCamera />
                            Start Camera
                        </button>
                        {allowUpload && (
                            <button
                                type="button"
                                className="wc-btn wc-btn-upload"
                                onClick={() => fileInputRef.current?.click()}
                                disabled={disabled}
                            >
                                <FaUpload />
                                Upload Photo
                            </button>
                        )}
                    </div>
                    {error && <p className="wc-error">{error}</p>}
                </div>
            )}

            {/* STREAMING STATE — show live video + capture button */}
            {cameraState === "streaming" && (
                <div className="wc-preview">
                    <div className="wc-video-wrapper">
                        <video
                            ref={videoRef}
                            autoPlay
                            playsInline
                            muted
                            className="wc-video"
                            style={{ transform: "scaleX(-1)" }}
                        />
                        <div className="wc-video-overlay">
                            <div className="wc-video-frame" />
                        </div>
                    </div>
                    <div className="wc-actions">
                        <button
                            type="button"
                            className="wc-btn wc-btn-capture"
                            onClick={capturePhoto}
                            disabled={disabled}
                        >
                            <FaCamera />
                            Capture
                        </button>
                        <button
                            type="button"
                            className="wc-btn wc-btn-cancel"
                            onClick={removePhoto}
                            disabled={disabled}
                        >
                            <FaTimes />
                            Cancel
                        </button>
                    </div>
                </div>
            )}

            {/* CAPTURED STATE — show captured image + retry */}
            {cameraState === "captured" && capturedImage && (
                <div className="wc-preview">
                    <div className="wc-video-wrapper">
                        <img src={capturedImage} alt="Captured" className="wc-captured-img" />
                    </div>
                    <div className="wc-actions">
                        <button
                            type="button"
                            className="wc-btn wc-btn-retake"
                            onClick={retake}
                            disabled={disabled}
                        >
                            <FaRedo />
                            {allowUpload ? "Change Photo" : "Retake"}
                        </button>
                        <button
                            type="button"
                            className="wc-btn wc-btn-remove"
                            onClick={removePhoto}
                            disabled={disabled}
                        >
                            <FaTimes />
                            Remove
                        </button>
                    </div>
                    <p className="wc-captured-hint">Photo selected successfully ✓</p>
                </div>
            )}
        </div>
    );
}
