# ============================================================
# Python Face Verification Microservice Dockerfile
# Base: Python 3.10 Slim Bookworm with native dlib/cmake dependencies
# ============================================================

FROM python:3.10-slim-bookworm

# Install native dependencies required by dlib and face_recognition, plus curl for healthchecks
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    libopenblas-dev \
    liblapack-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create dedicated non-root service user
RUN useradd -r -u 1002 -d /app -s /sbin/nologin faceapp

WORKDIR /app

# Copy dependency requirements
COPY requirements.txt ./

# Install python dependencies:
# Use precompiled dlib-bin wheel and install face_recognition with --no-deps to prevent memory-exhausting source compilation of dlib
RUN pip install --no-cache-dir dlib-bin \
    && pip install --no-cache-dir flask==3.0.0 flask-cors==4.0.0 "numpy>=1.24.0" "Pillow>=10.0.0" "Click>=6.0" face-recognition-models \
    && pip install --no-cache-dir --no-deps face_recognition==1.3.0

# Copy service code
COPY face_service.py ./

# Create uploads mount directory and grant permissions
RUN mkdir -p /app/uploads/profile-pictures \
    && chown -R faceapp:faceapp /app \
    && chmod -R 775 /app

# Run as non-root service user
USER faceapp

EXPOSE 5001

# Production environment variables
ENV PORT=5001 \
    PYTHONUNBUFFERED=1 \
    FLASK_ENV=production \
    UPLOAD_DIR=/app/uploads/profile-pictures

# Health check probe
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:5001/health || exit 1

# Start Flask microservice
CMD ["python", "face_service.py"]
