#!/usr/bin/env bash
# ============================================================
# College Faculty Attendance Management System
# Disaster-Recovery Full Database Backup Utility (POSIX/Linux/macOS)
# ============================================================

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_BACKUP_DIR="${SCRIPT_DIR}/../backups"

BACKUP_DIR="${BACKUP_DIR:-$DEFAULT_BACKUP_DIR}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3307}"
DB_NAME="${DB_NAME:-college_attendance}"
DB_USER="${DB_USER:-root}"

if [[ -z "${DB_PASSWORD:-}" ]]; then
    echo "[ERROR] DB_PASSWORD environment variable is not set." >&2
    echo "Please set DB_PASSWORD securely before running this backup." >&2
    exit 1
fi

# Ensure backup directory exists with restricted permissions
mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}" 2>/dev/null || true

# Timestamped filename: YYYYMMDD_HHMMSS
TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
BACKUP_FILE="${BACKUP_DIR}/${DB_NAME}_backup_${TIMESTAMP}.sql"

# Overwrite protection
if [[ -e "${BACKUP_FILE}" ]]; then
    echo "[ERROR] Target backup file already exists: ${BACKUP_FILE}" >&2
    echo "Overwrite protection active. Aborting to prevent data loss." >&2
    exit 1
fi

# Verify mysqldump binary
if ! command -v mysqldump >/dev/null 2>&1; then
    echo "[ERROR] 'mysqldump' command not found in PATH." >&2
    exit 1
fi

# Create secure temporary options file to avoid exposing credentials via command line
TEMP_CNF="$(mktemp "${TMPDIR:-/tmp}/mysql_dr_XXXXXX.cnf")"
chmod 600 "${TEMP_CNF}"

cleanup() {
    if [[ -f "${TEMP_CNF}" ]]; then
        rm -f "${TEMP_CNF}"
    fi
}
trap cleanup EXIT INT TERM

cat <<EOF > "${TEMP_CNF}"
[client]
host=${DB_HOST}
port=${DB_PORT}
user=${DB_USER}
password="${DB_PASSWORD}"
EOF

echo "[INFO] Starting full database backup for '${DB_NAME}' on ${DB_HOST}:${DB_PORT}..."
echo "[INFO] Output target: ${BACKUP_FILE}"

if mysqldump --defaults-extra-file="${TEMP_CNF}" \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    --quick \
    --default-character-set=utf8mb4 \
    --set-gtid-purged=OFF \
    "${DB_NAME}" > "${BACKUP_FILE}"; then

    # Validate output file existence and non-zero size
    if [[ ! -s "${BACKUP_FILE}" ]]; then
        echo "[ERROR] Backup file is empty (0 bytes) or was not created." >&2
        rm -f "${BACKUP_FILE}"
        exit 1
    fi

    chmod 600 "${BACKUP_FILE}" 2>/dev/null || true
    FILE_SIZE="$(wc -c < "${BACKUP_FILE}" | tr -d ' ')"
    echo "[SUCCESS] Full database backup completed successfully."
    echo "[INFO] File: ${BACKUP_FILE} (Size: ${FILE_SIZE} bytes)"
    exit 0
else
    STATUS=$?
    echo "[ERROR] mysqldump failed with exit code ${STATUS}." >&2
    rm -f "${BACKUP_FILE}"
    exit "${STATUS}"
fi
