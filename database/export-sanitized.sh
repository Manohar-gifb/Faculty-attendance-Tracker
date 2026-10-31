#!/usr/bin/env bash
# ============================================================
# College Faculty Attendance Management System
# Sanitized Development Export Utility (POSIX/Linux/macOS)
# ============================================================

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_BACKUP_DIR="${SCRIPT_DIR}/../backups"

BACKUP_DIR="${BACKUP_DIR:-$DEFAULT_BACKUP_DIR}"
STAGE_HOST="${STAGE_HOST:-localhost}"
STAGE_PORT="${STAGE_PORT:-33071}"
STAGE_DB="${STAGE_DB:-college_attendance_stage}"
STAGE_USER="${STAGE_USER:-ph31testuser}"

if [[ -z "${STAGE_PASSWORD:-}" ]]; then
    echo "[ERROR] STAGE_PASSWORD environment variable is not set." >&2
    echo "Please set STAGE_PASSWORD for the disposable/staging database." >&2
    exit 1
fi

# CRITICAL SAFETY GUARD: STRICT PROTECTION OF LIVE PRODUCTION DATABASE
if [[ "${STAGE_PORT}" == "3307" && "${STAGE_DB}" == "college_attendance" ]]; then
    echo "[FATAL ERROR] Sanitization CANNOT be executed directly against the live production database!" >&2
    echo "Live database protection active: localhost:3307/college_attendance is strictly protected." >&2
    echo "Please specify a separate disposable or staging database on port 33071." >&2
    exit 1
fi

mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}" 2>/dev/null || true

TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
EXPORT_FILE="${BACKUP_DIR}/${STAGE_DB}_sanitized_${TIMESTAMP}.sql"

if [[ -e "${EXPORT_FILE}" ]]; then
    echo "[ERROR] Target export file already exists: ${EXPORT_FILE}" >&2
    exit 1
fi

if ! command -v mysql >/dev/null 2>&1 || ! command -v mysqldump >/dev/null 2>&1; then
    echo "[ERROR] 'mysql' and 'mysqldump' commands must both be available in PATH." >&2
    exit 1
fi

TEMP_CNF="$(mktemp "${TMPDIR:-/tmp}/mysql_san_XXXXXX.cnf")"
chmod 600 "${TEMP_CNF}"

cleanup() {
    if [[ -f "${TEMP_CNF}" ]]; then
        rm -f "${TEMP_CNF}"
    fi
}
trap cleanup EXIT INT TERM

cat <<EOF > "${TEMP_CNF}"
[client]
host=${STAGE_HOST}
port=${STAGE_PORT}
user=${STAGE_USER}
password="${STAGE_PASSWORD}"
EOF

echo "[INFO] Applying sanitization rules on staging database '${STAGE_DB}' at ${STAGE_HOST}:${STAGE_PORT}..."
mysql --defaults-extra-file="${TEMP_CNF}" "${STAGE_DB}" < "${SCRIPT_DIR}/sanitize-export.sql"

echo "[INFO] Exporting sanitized snapshot to ${EXPORT_FILE}..."
mysqldump --defaults-extra-file="${TEMP_CNF}" \
    --single-transaction \
    --quick \
    --default-character-set=utf8mb4 \
    --set-gtid-purged=OFF \
    "${STAGE_DB}" > "${EXPORT_FILE}"

chmod 600 "${EXPORT_FILE}" 2>/dev/null || true
echo "[SUCCESS] Sanitized development export created successfully."
echo "[WARNING] THIS FILE IS A SANITIZED DEVELOPMENT SNAPSHOT - NOT FOR DISASTER RECOVERY."
echo "[INFO] File: ${EXPORT_FILE}"
exit 0
