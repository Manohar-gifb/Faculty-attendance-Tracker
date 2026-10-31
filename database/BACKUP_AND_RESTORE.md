# Database Backup, Safe Restore, and Disaster Recovery Guide

**College Faculty Attendance & Management System**
**Document Version:** 1.0.0 (Phase 6: Plan 06-02)
**Classification:** Operational Runbook / System Administration

---

## 1. Overview & Separation of Concerns

To safeguard institutional faculty attendance records, biometric integrity, and leave audit trails, database maintenance is separated into two fundamentally distinct workflows:

1. **Full Disaster-Recovery (DR) Backups:**
   - **Purpose:** Faithful point-in-time snapshot of the entire database schema, table data, foreign keys, BCrypt password hashes, and audit history.
   - **Integrity Requirement:** Zero data loss, zero mutation, zero sanitization.
   - **Storage & Security:** Highly confidential; restricted filesystem permissions (`chmod 600`), off-site encrypted storage.
   - **Tooling:** `database/backup.bat` (Windows) and `database/backup.sh` (Linux/macOS).

2. **Sanitized Development & Demonstration Exports:**
   - **Purpose:** Anonymized snapshot for developer onboarding, bug reproduction, and staging tests without exposing real faculty personal data or security credentials.
   - **Sanitization Transformation:** All user passwords replaced with a standard non-production hash (`DevTest@123`), personal emails anonymized, phone numbers masked, biometrics and GPS coordinates scrubbed, security tokens purged.
   - **Safety Invariant:** Never executed on the live production database; executed only against an isolated clone or staging instance.
   - **Tooling:** `database/sanitize-export.sql`, `database/export-sanitized.bat`, `database/export-sanitized.sh`.

---

## 2. Disaster-Recovery Backup Execution

Both the Windows and POSIX backup scripts use MySQL's native `mysqldump` utility with `--single-transaction`, `--routines`, `--triggers`, and `--events` to guarantee consistent, non-locking backups.

### Credential Security Guarantee
Passwords are **NEVER passed as command-line arguments** (which exposes them in process listings such as `ps -ef` or Windows Task Manager). Instead, scripts dynamically write credentials to a temporary options file passed via `--defaults-extra-file`, enforce restricted file permissions, and guarantee its immediate deletion upon exit (via shell traps in bash, or sequential cleanup in batch).

### A. Windows Execution (`database\backup.bat`)

Set environment variables securely in your session or scheduler:
```cmd
set DB_HOST=localhost
set DB_PORT=3307
set DB_NAME=college_attendance
set DB_USER=root
set DB_PASSWORD=your_production_password
set BACKUP_DIR=C:\FacultyAttendance\backups

database\backup.bat
```

Output file format:
`%BACKUP_DIR%\college_attendance_backup_YYYYMMDD_HHMMSS.sql`

### B. Linux / macOS Execution (`database/backup.sh`)

```bash
export DB_HOST="localhost"
export DB_PORT="3307"
export DB_NAME="college_attendance"
export DB_USER="root"
export DB_PASSWORD="your_production_password"
export BACKUP_DIR="/var/backups/faculty_attendance"

chmod +x database/backup.sh
./database/backup.sh
```

Output file format:
`${BACKUP_DIR}/college_attendance_backup_YYYYMMDD_HHMMSS.sql` (permissions: `0600`)

---

## 3. Automated Scheduling

### Windows Task Scheduler (Daily 02:00 AM)

To register a daily automated backup task using `schtasks`:
```cmd
schtasks /create /tn "FacultyAttendance_DailyBackup" ^
    /tr "cmd.exe /c set DB_PORT=3307 && set DB_PASSWORD=your_secure_password && C:\FacultyAttendance\database\backup.bat" ^
    /sc daily /st 02:00 /ru "SYSTEM"
```
*Note: In production environments, credentials should be loaded from a secure environment file or Windows Credential Manager rather than embedded in the task definition.*

### Linux Crontab (Daily 02:00 AM)

Add the following entry to `/etc/cron.d/faculty_attendance_backup`:
```cron
0 2 * * * college-app DB_PORT=3307 DB_PASSWORD="your_secure_password" /opt/faculty_application/database/backup.sh >> /var/log/faculty_backup.log 2>&1
```

---

## 4. Retention & Off-Site Encryption

1. **Local Retention Recommendation (Grandfather-Father-Son):**
   - Keep 7 daily backups.
   - Keep 4 weekly backups.
   - Keep 12 monthly backups.
2. **Off-Site Transfer & GPG Encryption:**
   Disaster recovery backups must not reside exclusively on the application host. An encrypted copy should be transferred to secure cold storage:
   ```bash
   gpg --symmetric --cipher-algo AES256 -o "${BACKUP_FILE}.gpg" "${BACKUP_FILE}"
   scp "${BACKUP_FILE}.gpg" backup-vault@remote-storage.college.edu:/vault/backups/
   ```

---

## 5. Sanitized Development Export

To share data with developers or test staging environments:

1. Restore a disaster-recovery backup into an **isolated disposable database** (e.g. `localhost:33071/college_attendance_stage`).
2. Run the sanitized export script:
   ```cmd
   set STAGE_PORT=33071
   set STAGE_DB=college_attendance_stage
   set STAGE_USER=ph31testuser
   set STAGE_PASSWORD=ph31_test_user_pw

   database\export-sanitized.bat
   ```
3. **Live Database Guard:** Both `export-sanitized.bat` and `export-sanitized.sh` detect if `STAGE_PORT=3307` and `STAGE_DB=college_attendance`, and immediately abort with a fatal error.
4. **Resulting Dataset:** Outputs `college_attendance_sanitized_YYYYMMDD_HHMMSS.sql`. All faculty passwords become `DevTest@123` (`$2a$10$...`), biometrics and GPS coordinates are cleared to `NULL`, personal phone/bio details are redacted, and the file contains a header warning:
   `-- WARNING: SANITIZED DEVELOPMENT SNAPSHOT - NOT FOR DISASTER RECOVERY`

---

## 6. Safe Database Restoration

### Safety Guard Against Production Overwrites
Accidentally running a restore against the live production database (`localhost:3307/college_attendance`) will overwrite attendance records and cause immediate data loss.

To prevent this:
- `database/restore.bat` and `database/restore.sh` **strictly refuse** to restore into `college_attendance` on port `3307`.
- Restoration is permitted only into explicit target databases (e.g. disaster-recovery staging or disposable test databases on port 33071).

### Restore Drill Procedure (Disposable Database)

1. Create a clean disposable database:
   ```sql
   CREATE DATABASE college_attendance_drill CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
2. Execute the restore utility:
   ```cmd
   set RESTORE_HOST=localhost
   set RESTORE_PORT=33071
   set RESTORE_DB=college_attendance_drill
   set RESTORE_USER=ph31testuser
   set RESTORE_PASSWORD=ph31_test_user_pw

   database\restore.bat backups\college_attendance_backup_20261028_141628.sql
   ```
3. Verify relational integrity:
   - Verify table count (`departments`, `users`, `attendance_logs`, `leave_requests`, `audit_logs`).
   - Verify record counts match pre-backup counts.
   - Verify foreign keys and unique constraints are intact.
