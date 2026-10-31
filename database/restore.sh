#!/usr/bin/env bash
# ============================================================
# College Faculty Attendance Management System
# Safe Database Restore Utility (POSIX/Linux/macOS)
# ============================================================

set -euo pipefail
umask 077

if [[ $# -lt 1 ]]; then
    echo "[ERROR] Usage: ./database/restore.sh <path_to_backup_file.sql>" >&2
    echo "An explicit backup file argument is required." >&2
    exit 1
fi

BACKUP_FILE="$1"

if [[ ! -f "${BACKUP_FILE}" ]]; then
    echo "[ERROR] Backup file does not exist: ${BACKUP_FILE}" >&2
    exit 1
fi

if [[ ! -s "${BACKUP_FILE}" ]]; then
    echo "[ERROR] Backup file is empty (0 bytes). Refusing to restore an empty file." >&2
    exit 1
fi

if [[ -z "${RESTORE_DB:-}" ]]; then
    echo "[ERROR] RESTORE_DB environment variable is not set." >&2
    echo "An explicit target database name is required to prevent accidental overwrites." >&2
    exit 1
fi

RESTORE_HOST="${RESTORE_HOST:-localhost}"
RESTORE_PORT="${RESTORE_PORT:-33071}"
RESTORE_USER="${RESTORE_USER:-ph31testuser}"

if [[ -z "${RESTORE_PASSWORD:-}" ]]; then
    echo "[ERROR] RESTORE_PASSWORD environment variable is not set." >&2
    echo "Please set RESTORE_PASSWORD for target database ${RESTORE_DB}." >&2
    exit 1
fi

# CRITICAL SAFETY GUARD: STRICT PROTECTION OF LIVE PRODUCTION DATABASE
if [[ "${RESTORE_PORT}" == "3307" && "${RESTORE_DB}" == "college_attendance" ]]; then
    echo "[FATAL ERROR] Restoring directly into the live production database 'localhost:3307/college_attendance' is strictly prohibited!" >&2
    echo "Live database protection active: this utility only permits restoring into isolated, disposable, or disaster-recovery test databases." >&2
    exit 1
fi

if ! command -v mysql >/dev/null 2>&1; then
    echo "[ERROR] 'mysql' command not found in PATH." >&2
    exit 1
fi

TEMP_CNF="$(mktemp "${TMPDIR:-/tmp}/mysql_rst_XXXXXX.cnf")"
chmod 600 "${TEMP_CNF}"

cleanup() {
    if [[ -f "${TEMP_CNF}" ]]; then
        rm -f "${TEMP_CNF}"
    fi
}
trap cleanup EXIT INT TERM

cat <<EOF > "${TEMP_CNF}"
[client]
host=${RESTORE_HOST}
port=${RESTORE_PORT}
user=${RESTORE_USER}
password="${RESTORE_PASSWORD}"
EOF

echo "[INFO] Verifying target database connectivity on ${RESTORE_HOST}:${RESTORE_PORT}..."
if ! mysql --defaults-extra-file="${TEMP_CNF}" -e "SELECT 1;" "${RESTORE_DB}" >/dev/null 2>&1; then
    echo "[ERROR] Cannot connect to target database '${RESTORE_DB}' on ${RESTORE_HOST}:${RESTORE_PORT}." >&2
    echo "Please ensure the target database exists and credentials are valid." >&2
    exit 1
fi

echo "[INFO] Restoring '${BACKUP_FILE}' into '${RESTORE_DB}' on ${RESTORE_HOST}:${RESTORE_PORT}..."
mysql --defaults-extra-file="${TEMP_CNF}" "${RESTORE_DB}" < "${BACKUP_FILE}"

echo "[SUCCESS] Database restoration completed successfully into '${RESTORE_DB}'."
exit 0
