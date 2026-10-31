# Operations and Deployment Runbook
## Faculty Sync Tracker
*(Formerly College Faculty Attendance Management System)*

**Version:** 1.0.0-PROD
**Applicability:** Production Operations, Collegiate IT Administrators, DevOps Engineers
**System Architecture:** React 18 (Nginx), Spring Boot 3.2.5 (Java 17 Temurin), Flask (Python 3.10), MySQL 8.0

---

## Table of Contents

1. [Architecture Overview & Prerequisites](#1-architecture-overview--prerequisites)
2. [Environment Preparation & Secret Management](#2-environment-preparation--secret-management)
3. [Deployment & Container Orchestration](#3-deployment--container-orchestration)
4. [Database Safety, Flyway & Disaster Recovery](#4-database-safety-flyway--disaster-recovery)
5. [Profile Picture Storage & Continuity](#5-profile-picture-storage--continuity)
6. [HTTPS, TLS Termination & Reverse Proxy Integration](#6-https-tls-termination--reverse-proxy-integration)
7. [Operational Monitoring, Health Probes & Incident Escalation](#7-operational-monitoring-health-probes--incident-escalation)

---

## 1. Architecture Overview & Prerequisites

### 1.1 Microservice Topology & Port Isolation

```
                                  +------------------------------------+
                                  |         Internet / Campus LAN      |
                                  +------------------------------------+
                                                    |
                                    HTTPS Port 443  | (Host Reverse Proxy)
                                                    v
                                  +------------------------------------+
                                  |         Host Reverse Proxy         |
                                  |      (Nginx / Traefik / SSL)       |
                                  +------------------------------------+
                                                    |
                                     HTTP Port 3000 | (Private Loopback)
                                                    v
+============================= Docker Compose Network (faculty_network) =============================+
|                                                                                                  |
|   +--------------------------+                                                                   |
|   |         frontend         |  Port 80 (Internal)                                               |
|   |   (React 18 / Nginx)     |  Serves SPA static bundle & proxies /api/, /uploads/              |
|   +--------------------------+                                                                   |
|         |               |                                                                        |
|         | /api/*        | /uploads/*                                                             |
|         v               v                                                                        |
|   +--------------------------+                Internal HTTP              +-------------------+   |
|   |         backend          | ----------------------------------------> |   face-service    |   |
|   |   (Spring Boot 3.2.5)    |     http://face-service:5001              | (Python 3.10)     |   |
|   +--------------------------+                                           +-------------------+   |
|         |                                                                    |                   |
|         | JDBC (Port 3306)                                                   | Read-Only Mount   |
|         v                                                                    v                   |
|   +--------------------------+                                      +--------------------+       |
|   |            db            |                                      |    Named Volume    |       |
|   |       (MySQL 8.0)        |                                      | faculty_uploads_data       |
|   +--------------------------+                                      +--------------------+       |
|         |                                                                                        |
|         | Persistent Volume Mount                                                                |
|         v                                                                                        |
|   +--------------------------+                                                                   |
|   |       Named Volume       |                                                                   |
|   |    faculty_mysql_data    |                                                                   |
|   +--------------------------+                                                                   |
+==================================================================================================+
```

### 1.2 Default Network Boundaries

- **Publicly Exposed Ports:** ONLY the `frontend` container publishes port `3000:80` (or host port defined by `FRONTEND_PORT`).
- **Internal Private Services:**
  - `backend` (Spring Boot, port 8081): Reachable ONLY via Nginx proxy on `faculty_network`.
  - `face-service` (Python Flask, port 5001): Reachable ONLY by `backend` on `faculty_network`. NO port published to host.
  - `db` (MySQL, port 3306): Reachable ONLY by `backend` on `faculty_network`. NO port published to host.

### 1.3 System Prerequisites

- **Host OS:** Linux (Ubuntu 22.04 LTS recommended) or Windows Server 2022 / Windows 10/11 Pro with WSL2.
- **Hardware:**
  - Minimum: 2 CPU cores, 4 GB RAM, 20 GB SSD storage.
  - Recommended: 4 CPU cores, 8 GB RAM, 50 GB SSD storage (for face encodings and biometric matching).
- **Software Dependencies:**
  - Docker Engine 20.10+ (or Docker Desktop 4.0+)
  - Docker Compose v2.20+ (`docker compose`)
  - OpenSSL (for secret generation)
  - `curl` / `wget` (for health monitoring)

---

## 2. Environment Preparation & Secret Management

### 2.1 Generating Cryptographically Strong Secrets

Before launching the application in production, you MUST generate unique, high-entropy secrets. Never reuse development secrets or committed defaults.

Run the following commands on a secure terminal:

```bash
# 1. Generate 256-bit Base64 JWT Secret Key
openssl rand -base64 48

# 2. Generate Inter-Service Shared Secret (Spring Boot <-> Face Microservice)
openssl rand -hex 24

# 3. Generate Database Passwords (24+ characters)
openssl rand -base64 18
```

### 2.2 Environment Configuration File (`.env`)

Copy the template from the project root:

```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` and fill in all production variables:

```ini
# ============================================================
# DATABASE CONFIGURATION (MySQL 8.0)
# ============================================================
DB_NAME=college_attendance
DB_USERNAME=faculty_user
DB_PASSWORD=<GENERATE_STRONG_PASSWORD_HERE>
DB_ROOT_PASSWORD=<GENERATE_STRONG_ROOT_PASSWORD_HERE>

# ============================================================
# APPLICATION SECRETS (MANDATORY FAIL-CLOSED IN PROD)
# ============================================================
JWT_SECRET=REPLACE_WITH_GENERATED_SECRET_DO_NOT_USE
JWT_EXPIRATION_MS=86400000

INTERNAL_SERVICE_SECRET=<PASTE_24_CHAR_HEX_SECRET_HERE>

# ============================================================
# WEB & NETWORKING
# ============================================================
FRONTEND_PORT=3000
FRONTEND_URL=https://attendance.college.edu
CORS_ALLOWED_ORIGINS=https://attendance.college.edu

# ============================================================
# DATABASE MIGRATIONS (FLYWAY) & DDL
# ============================================================
FLYWAY_ENABLED=false
DDL_AUTO=validate

# ============================================================
# SMTP EMAIL NOTIFICATIONS
# ============================================================
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=notifications@college.edu
MAIL_PASSWORD=<APP_SPECIFIC_PASSWORD>
```

> [!CAUTION]
> **Fail-Closed Protection:** The backend `ProductionSecurityValidator` executes on startup whenever `SPRING_PROFILES_ACTIVE=prod`. If `JWT_SECRET`, `INTERNAL_SERVICE_SECRET`, or `DB_PASSWORD` are missing or match known development defaults, Spring Boot **aborts startup immediately** with an `IllegalStateException`.

---

## 3. Deployment & Container Orchestration

### 3.1 Pre-Flight Configuration Validation

Before building or running containers, validate the Compose file and environment interpolation:

```bash
# Verify Compose parses correctly without error
docker compose config
```

If any mandatory variable is missing, Compose fails closed with an error such as:
`required variable JWT_SECRET is missing a value: JWT_SECRET is required`

### 3.2 Building Production Images

Build all service images using Docker BuildKit:

```bash
DOCKER_BUILDKIT=1 docker compose build
```

This builds:
1. `faculty-frontend:1.0.0` (Node 18 build $\rightarrow$ Nginx 1.25 Alpine runtime).
2. `faculty-backend:1.0.0` (Temurin JDK 17 build $\rightarrow$ Temurin JRE 17 runtime, non-root user `spring`).
3. `faculty-face-service:1.0.0` (Debian Bookworm Python 3.10 runtime, non-root user `faceapp`).

### 3.3 Starting the Application Stack

Start all services in detached mode:

```bash
docker compose up -d
```

### 3.4 Verification of Service Health

Check container health status and restart policies:

```bash
docker compose ps
```

All 4 services should display status `Up (healthy)`:

```
NAME                    IMAGE                        COMMAND                  SERVICE        STATUS
faculty_backend         faculty-backend:1.0.0        "java -XX:+UseContai…"   backend        Up (healthy)
faculty_db              mysql:8.0.36                 "mysqld --character-…"   db             Up (healthy)
faculty_face_service    faculty-face-service:1.0.0   "python face_service…"   face-service   Up (healthy)
faculty_frontend        faculty-frontend:1.0.0       "nginx -g 'daemon of…"   frontend       Up (healthy)
```

Direct health probe verification:

```bash
# Frontend probe
curl -I http://localhost:3000/

# Backend health probe (via Nginx reverse proxy)
curl -s http://localhost:3000/api/health

# Expected response:
# {"status":"UP","service":"faculty-backend","timestamp":1730117400000}
```

### 3.5 Viewing Container Logs

Inspect live streaming logs across all services:

```bash
# All services
docker compose logs -f --tail=50

# Specific service
docker compose logs -f backend
docker compose logs -f face-service
```

### 3.6 Graceful Shutdown & Restart

```bash
# Stop all services gracefully (sends SIGTERM, waits 10s before SIGKILL)
docker compose stop

# Restart all services
docker compose restart

# Complete teardown (preserves volumes faculty_mysql_data and faculty_uploads_data)
docker compose down
```

> [!WARNING]
> Never run `docker compose down -v` in production! The `-v` flag deletes all persistent Docker volumes, permanently destroying database files and uploaded profile photos.

---

## 4. Database Safety, Flyway & Disaster Recovery

### 4.1 Compose Database vs. External Institutional Database

The application supports two distinct database deployment models:

1. **Docker Compose Database (`db` service):**
   - Runs `mysql:8.0.36` in container `faculty_db`.
   - Data persists in Docker volume `faculty_mysql_data`.
   - Used for self-contained, single-server collegiate installations.
   - Host `localhost:3307` is NEVER used or contacted by this container.

2. **External Institutional Database (e.g., Campus MySQL Cluster / AWS RDS):**
   - If your institution hosts its own centralized MySQL server, comment out the `db` service in `docker-compose.yml`.
   - Set `DB_URL` in `.env`:
     `DB_URL=jdbc:mysql://mysql.internal.college.edu:3306/college_attendance?useSSL=true&serverTimezone=UTC`
   - Supply institutionally managed `DB_USERNAME` and `DB_PASSWORD`.

> [!IMPORTANT]
> **Pre-Deployment Database Verification:**
> Before deploying, verify what database host and port are resolved:
> ```bash
> docker compose exec backend sh -c 'echo "DB_URL: $DB_URL"'
> ```
> Never point container initialization scripts at `localhost:3307/college_attendance`.

### 4.2 Prohibited Commands on Live Production Database

The following commands are **STRICTLY PROHIBITED** on the live production database:
- `DROP DATABASE college_attendance;`
- `DROP TABLE ...;`
- `TRUNCATE TABLE ...;`
- Running Hibernate with `ddl-auto=create` or `ddl-auto=create-drop`.
- Running Flyway without prior schema-baseline verification.

### 4.3 Flyway Versioned Migrations & Safe Adoption

Flyway is governed by the safe adoption protocol established in Phase 6:
- By default, `FLYWAY_ENABLED=false`. Schema management remains conservative with `DDL_AUTO=validate`.
- **To Enable Flyway in Production:**
  1. Confirm `V1__baseline_schema.sql` matches the live database:
     ```bash
     ./mvnw test -Dtest=SchemaBaselineTest
     ```
  2. Perform a disaster-recovery backup first (`database/backup.sh` or `.bat`).
  3. In `.env`, set:
     ```ini
     FLYWAY_ENABLED=true
     DDL_AUTO=validate
     ```
  4. On startup, Flyway detects the existing populated schema, applies `baseline-version=1` without executing V1 against live data, and only applies subsequent incremental migrations (`V2__...`).

### 4.4 Automated Disaster-Recovery Backups

Backups are executed using MySQL option files (`--defaults-extra-file`) to ensure passwords are never exposed in command-line arguments, process lists, or shell history.

**Windows Backup Execution:**
```cmd
cd database
backup.bat
```

**Linux / macOS Backup Execution:**
```bash
cd database
chmod +x backup.sh
./backup.sh
```

**Output:**
Timestamped, non-overwriting backup archive in `backups/dr_backup_YYYYMMDD_HHMMSS.sql`.

### 4.5 Disposable Database Restore Drill (Mandatory Quarterly Verification)

A backup is NOT verified until it has been successfully restored into an isolated, disposable database.

> [!CAUTION]
> NEVER test database restoration against the live production port `3307` or container `faculty_db`!

**Restore Drill Procedure:**
1. Start an isolated disposable MySQL instance on port `33071`:
   ```bash
   docker run -d --name disposable_mysql -p 33071:3306 -e MYSQL_ROOT_PASSWORD=testroot -e MYSQL_DATABASE=disposable_test mysql:8.0.36
   ```
2. Execute the restore script targeting port `33071`:
   ```bash
   # Windows
   restore.bat ..\backups\dr_backup_20261028_120000.sql localhost 33071 disposable_test root testroot

   # Linux
   ./restore.sh ../backups/dr_backup_20261028_120000.sql localhost 33071 disposable_test root testroot
   ```
3. Verify row counts and table integrity:
   ```bash
   docker exec disposable_mysql mysql -u root -ptestroot disposable_test -e "SELECT count(*) FROM users; SELECT count(*) FROM attendance_logs;"
   ```
4. Tear down the disposable container:
   ```bash
   docker rm -f disposable_mysql
   ```

---

## 5. Profile Picture Storage & Continuity

### 5.1 Host Directory vs. Docker Named Volume

- **Windows Host Directory:** `C:/FacultyAttendance/uploads/profile-pictures` (Contains live faculty photos uploaded in pre-container operations).
- **Docker Production Volume:** `faculty_uploads_data` (Mapped to `/var/faculty/uploads/profile-pictures` in `backend`).

> [!WARNING]
> Named Docker volumes are stored inside Docker's internal storage area (e.g. `/var/lib/docker/volumes/` or WSL2 virtual disk). Creating `faculty_uploads_data` does **NOT** automatically copy existing host files from `C:/FacultyAttendance`.

### 5.2 Safe Non-Destructive Photo Migration Procedure

To migrate existing Windows host photos into the production Docker volume without risking data loss:

1. **Step 1: Inventory Existing Host Files**
   ```powershell
   $hostPhotos = Get-ChildItem -File C:\FacultyAttendance\uploads\profile-pictures
   Write-Host "Host Photo Count:" $hostPhotos.Count
   ```

2. **Step 2: Compare Database References**
   Connect to the database and query how many users reference profile photos:
   ```sql
   SELECT count(*) FROM users WHERE profile_photo IS NOT NULL;
   ```

3. **Step 3: Copy Files into the Named Volume**
   Use a temporary worker container to copy files with overwrite protection:
   ```bash
   docker run --rm \
     -v faculty_uploads_data:/volume_target \
     -v C:/FacultyAttendance/uploads/profile-pictures:/host_source:ro \
     alpine sh -c "cp -rn /host_source/. /volume_target/ && chown -R 1001:0 /volume_target && chmod -R 775 /volume_target"
   ```

4. **Step 4: Verify Volume File Count**
   ```bash
   docker run --rm -v faculty_uploads_data:/volume_target alpine sh -c "ls -1 /volume_target | wc -l"
   ```
   Confirm the count matches the host count from Step 1.

5. **Step 5: Rollback Guarantee**
   Do **NOT** delete or rename `C:/FacultyAttendance/uploads/profile-pictures`. Keep the host folder as an immediate offline cold copy. If containerization is ever rolled back, the host folder remains 100% intact.

### 5.3 Service Permission Specifications

- `backend` (Spring Boot): Runs as user `spring` (UID 1001), group `root` (GID 0). Requires **Read-Write** permissions (`chmod 775`) to write new uploaded photos and prune obsolete photos during scheduled cleanup sweeps.
- `face-service` (Python Flask): Runs as user `faceapp` (UID 1002). Mounted as **Read-Only** (`:ro`) in `docker-compose.yml` (`uploads_data:/app/uploads/profile-pictures:ro`). The biometric service never writes or deletes images.

---

## 6. HTTPS, TLS Termination & Reverse Proxy Integration

### 6.1 Supported TLS Termination Architecture

In production collegiate deployments, SSL/TLS certificates MUST terminate at the host perimeter. The internal Docker Compose stack listens on HTTP port `3000`.

```
Client Browser (HTTPS :443)
        |
        v
Host Nginx Reverse Proxy (SSL Termination + Let's Encrypt Certbot)
        |
        v HTTP :3000 (Localhost loopback)
Frontend Container (Nginx)
        |
        v Internal Docker Network
Backend Container (Spring Boot)
```

### 6.2 Host Nginx Configuration Template (`/etc/nginx/sites-available/faculty.conf`)

Install and configure on the host OS:

```nginx
# HTTP - Redirect all traffic to HTTPS
server {
    listen 80;
    listen [::]:80;
    server_name attendance.college.edu;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# HTTPS - Terminate SSL and Proxy to Docker Compose
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name attendance.college.edu;

    # SSL Certificate Paths (Certbot / Let's Encrypt)
    ssl_certificate /etc/letsencrypt/live/attendance.college.edu/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/attendance.college.edu/privkey.pem;
    ssl_trusted_certificate /etc/letsencrypt/live/attendance.college.edu/chain.pem;

    # Modern TLS Hardening
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    # HSTS (Strict-Transport-Security)
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;

    # Reverse proxy to Docker Compose frontend container
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;

        # Critical: Forward host headers so Spring Boot generates correct HTTPS email links
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Port 443;

        proxy_connect_timeout 15s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }
}
```

### 6.3 Certificate Issuance & Renewal (Certbot)

```bash
# Initial issuance
sudo certbot certonly --webroot -w /var/www/certbot -d attendance.college.edu

# Verify automatic renewal timer
sudo systemctl status certbot.timer
```

---

## 7. Operational Monitoring, Health Probes & Incident Escalation

### 7.1 Automated Health Probes

| Service | Target URL / Probe | Expected Status | Interval | Purpose |
|---|---|---|---|---|
| **Frontend** | `wget -q -O - http://localhost/` | HTTP 200 | 20s | Confirms Nginx SPA serving |
| **Backend** | `curl -f http://localhost:8081/api/health` | HTTP 200 `{"status":"UP"}` | 15s | Confirms Spring Boot & DB connectivity |
| **Face Service**| `curl -f http://localhost:5001/health` | HTTP 200 `{"status":"ok"}` | 15s | Confirms Flask biometric engine |
| **Database** | `mysqladmin ping -u $USER -p$PASS` | `mysqld is alive` | 10s | Confirms MySQL InnoDB engine |

### 7.2 Incident Escalation & Response Playbooks

#### Scenario A: Backend Fails to Start (Status `Restarting`)
1. Inspect logs: `docker compose logs backend`
2. Look for `ProductionSecurityValidator`:
   - If log shows `PRODUCTION SECURITY VALIDATION FAILED`: One of `JWT_SECRET`, `INTERNAL_SERVICE_SECRET`, or `DB_PASSWORD` is using the development fallback key. Update `.env` with a unique secret and rerun.
3. Look for database connection failure:
   - Verify `db` container is healthy (`docker compose ps db`).
   - Confirm credentials in `.env` match `DB_USERNAME` and `DB_PASSWORD`.

#### Scenario B: Face Verification Service High Latency or Crash
1. Inspect face service memory: `docker stats faculty_face_service`
2. Native dlib face encodings consume CPU. If multi-user punches cause CPU throttling, increase Compose CPU allocations:
   ```yaml
   deploy:
     resources:
       limits:
         cpus: '2.0'
         memory: 2048M
   ```
3. Restart microservice: `docker compose restart face-service`

#### Scenario C: Disk Space Exhaustion from Profile Photos or Logs
1. Inspect Docker disk utilization: `docker system df`
2. Check logs size: Docker Compose is configured with `max-size: 20m` and `max-file: 5`, capping log storage at 100MB per service.
3. Check profile photo storage:
   - Spring Boot runs a scheduled daily sweep at 4:00 AM (`PhotoCleanupService`).
   - If disk is near capacity, operators can trigger an on-demand sweep via developer credentials or verify orphaned photos using `POST /api/developer/photo-cleanup/run`.

---
*College Faculty Attendance Management System — Operations & Deployment Runbook v1.0*
