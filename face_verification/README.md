# 👤 Face Verification Microservice

The Face Verification microservice is a lightweight Python Flask service that provides biometric facial verification for the College Faculty Attendance Management System.

It validates reference photos during faculty registration and performs facial recognition comparisons against webcam snapshots captured during faculty attendance punch-in.

---

## 🏗️ Architecture & Security Model

- **Host & Port:** Listens locally on `127.0.0.1:5001`. Only the local Spring Boot backend communicates with this service; it is never exposed directly to external networks.
- **Inter-Service Authentication:** All sensitive endpoints (`/verify-face` and `/detect-face`) require the `X-Internal-Secret` header matching the backend's configured shared secret.
- **Path Traversal Containment:** Reference image lookups are strictly restricted to configured upload directories (`ALLOWED_BASE_DIRS`). Relative path traversals (`../`) are rejected with `403 Forbidden`.
- **Payload & Memory Bounding:** Payloads are restricted to 10 MB (`MAX_CONTENT_LENGTH`). Oversized requests return HTTP `413 Request Entity Too Large` with JSON error messages. Base64 strings are validated prior to memory decoding, and image dimensions are bounded to $50 \times 50\text{px}$ minimum and $4096 \times 4096\text{px}$ maximum.
- **Anti-Proxying Multi-Face Enforcement:** Attendance verification (`/verify-face`) rejects frames containing more than one face (`match: false`), preventing proxy attendance and multi-person confusion.
- **Lighting Diagnostics:** Perceptual luminance analysis detects low-light ($< 40$) and overexposed ($> 245$) frames, returning actionable guidance to the user.

---

## 📋 System Prerequisites

- **Python:** 3.10 or 3.11 (64-bit recommended).
- **pip:** Up to date (`python -m pip install --upgrade pip`).

### Platform Build Tool Requirements

The underlying facial recognition library (`face_recognition`) relies on `dlib`, which contains C++ extensions.

#### 🪟 Windows
1. **Option A (Compile from source):** Install [Visual Studio C++ Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/) with the **"Desktop development with C++"** workload and ensure **CMake** is installed.
2. **Option B (Prebuilt wheels):** If C++ compiler tools cannot be installed, check for a prebuilt wheel matching your exact Python version and 64-bit architecture (e.g. `pip install dlib-bin` or a precompiled `.whl`), followed by `pip install --no-deps face_recognition` and the remaining dependencies.

#### 🐧 Linux (Ubuntu / Debian)
Install compilation tools, CMake, and linear algebra libraries:
```bash
sudo apt update
sudo apt install -y python3-dev build-essential cmake libopenblas-dev liblapack-dev
```

#### 🍎 macOS
Install CMake using Homebrew:
```bash
brew install cmake
```

---

## 🚀 Setup & Installation

### 1. Create a Virtual Environment

From the project root or the `face_verification/` directory:

```bash
cd face_verification
python -m venv .venv

# Activate on Windows:
.venv\Scripts\activate

# Activate on Linux / macOS:
source .venv/bin/activate
```

### 2. Install Python Dependencies

```bash
pip install -r requirements.txt
```

Verify the installation:
```bash
python -c "import flask, PIL, numpy, face_recognition; print('Dependencies installed successfully')"
```

---

## ⚙️ Configuration & Environment Variables

| Variable | Default Value | Description |
|:---|:---|:---|
| `PORT` | `5001` | Port on which the Flask microservice binds (`127.0.0.1`). |
| `INTERNAL_SERVICE_SECRET` | *(None - required)* | Shared secret key validated against incoming `X-Internal-Secret` headers. |
| `UPLOAD_DIR` | `<project-root>/uploads/profile-pictures` | Optional override for the profile pictures storage directory. |

---

## 🏃 Running the Service

### Using Automation Scripts (Recommended)

From the project root:
- **Windows:** Run `start-face-service.bat`
- **Linux / macOS:** Run `./start-face-service.sh`

These scripts automatically verify port availability, locate virtual environments, validate dependencies without silent overwrites, and propagate environment configuration safely without leaking secrets in console output.

### Running Directly

```bash
cd face_verification
python face_service.py
```

---

## 📡 API Specification

### 1. Health Probe (`GET /health`)
Used by Spring Boot during startup and dashboard diagnostics. Does not require authentication.

**Response:** `HTTP 200 OK`
```json
{
  "status": "ok",
  "service": "face-verification",
  "version": "1.0.0",
  "threshold": 0.60,
  "max_payload_mb": 10
}
```

---

### 2. Verify Attendance Face (`POST /verify-face`)
Compares captured webcam photo against the stored reference profile photo.

**Headers:**
- `Content-Type: application/json`
- `X-Internal-Secret: <shared-secret>`

**Request Body:**
```json
{
  "captured_photo": "data:image/jpeg;base64,...",
  "reference_photo_path": "uploads/profile-pictures/user_5_abc123.jpg"
}
```

**Response (`Match`):** `HTTP 200 OK`
```json
{
  "match": true,
  "confidence": 88.5,
  "distance": 0.3214,
  "message": "Face verified successfully"
}
```

**Response (`Multi-Face Detected`):** `HTTP 200 OK`
```json
{
  "match": false,
  "confidence": 0,
  "distance": 1.0,
  "message": "Multiple faces (2) detected in camera frame. Please ensure only you are visible."
}
```

**Response (`Low Lighting`):** `HTTP 200 OK`
```json
{
  "match": false,
  "confidence": 0,
  "distance": 1.0,
  "message": "No face detected. Low lighting detected — please face a light source and retry."
}
```

---

### 3. Detect Face during Registration (`POST /detect-face`)
Validates that an uploaded profile picture contains exactly one clear, un-occluded face.

**Headers:**
- `Content-Type: application/json`
- `X-Internal-Secret: <shared-secret>`

**Request Body:**
```json
{
  "captured_photo": "data:image/jpeg;base64,..."
}
```

**Response (`Valid`):** `HTTP 200 OK`
```json
{
  "valid": true,
  "faces_detected": 1,
  "message": "Face detected and validated successfully."
}
```

---

## 🧪 Testing

Run the automated test suite:

```bash
python -m unittest face_verification/test_face_service.py
```

All tests execute with isolated temporary directories and mock fixtures, testing health probes, authentication rejection, path containment, lighting diagnostics, multi-face rejection, and payload size bounds.
