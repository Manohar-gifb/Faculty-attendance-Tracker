#!/usr/bin/env bash
# ============================================================
# Launch script for Python Face Verification Microservice (Port 5001)
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/face_verification"

SERVICE_PORT="${PORT:-5001}"

echo "============================================================"
echo "  College Faculty Attendance - Face Verification Service"
echo "============================================================"

# 1. Check if port is already occupied
echo "Checking port ${SERVICE_PORT} availability..."
PORT_BUSY=0
if command -v lsof >/dev/null 2>&1; then
    if lsof -i ":${SERVICE_PORT}" >/dev/null 2>&1; then
        PORT_BUSY=1
    fi
elif command -v ss >/dev/null 2>&1; then
    if ss -lnt | grep -q ":${SERVICE_PORT} "; then
        PORT_BUSY=1
    fi
elif command -v netstat >/dev/null 2>&1; then
    if netstat -lnt | grep -q ":${SERVICE_PORT} "; then
        PORT_BUSY=1
    fi
fi

if [ "$PORT_BUSY" -eq 1 ]; then
    echo "[ERROR] Port ${SERVICE_PORT} is already occupied by another process."
    echo "Face Verification Service cannot bind to port ${SERVICE_PORT}."
    echo "Please stop the existing process before starting."
    exit 1
fi

# 2. Locate Python executable
PY_EXE=""
if [ -f "venv/bin/python" ]; then
    PY_EXE="venv/bin/python"
    echo "Using local virtual environment (venv)..."
elif [ -f ".venv/bin/python" ]; then
    PY_EXE=".venv/bin/python"
    echo "Using local virtual environment (.venv)..."
elif command -v python3 >/dev/null 2>&1; then
    PY_EXE="python3"
    echo "Using system python3..."
elif command -v python >/dev/null 2>&1; then
    PY_EXE="python"
    echo "Using system python..."
fi

if [ -z "$PY_EXE" ]; then
    echo "[ERROR] Python was not found. Please install Python 3.10+ and add it to your PATH."
    exit 1
fi

# 3. Verify required dependencies without silently mutating environment
echo "Checking Python dependencies..."
if ! "$PY_EXE" -c "import flask, flask_cors, PIL, numpy, face_recognition" >/dev/null 2>&1; then
    echo "[ERROR] One or more required Python dependencies are missing."
    echo "Please install dependencies by running:"
    echo ""
    echo "  cd face_verification"
    echo "  $PY_EXE -m pip install -r requirements.txt"
    echo ""
    echo "Consult face_verification/README.md for platform-specific prerequisites (CMake/OpenBLAS)."
    exit 1
fi

# 4. Safe secret propagation (never echo secret value)
if [ -n "$INTERNAL_SERVICE_SECRET" ]; then
    echo "Inter-service security secret: [Configured from environment]"
else
    echo "[WARNING] INTERNAL_SERVICE_SECRET is not set in environment."
fi

echo "Starting Face Verification Service on 127.0.0.1:${SERVICE_PORT}..."
echo "Press Ctrl+C to terminate the service."
echo "============================================================"

exec "$PY_EXE" face_service.py
