# Versioned Database Migrations (Flyway Safe Adoption Guide)

**College Faculty Attendance & Management System**
**Version:** 1.0.0 (Phase 6: Plan 06-01)

---

## 1. Overview & Architectural Philosophy

The College Faculty Attendance Management System introduces versioned database migrations via **Flyway** (`org.flywaydb:flyway-core` and `org.flywaydb:flyway-mysql`).

To protect live faculty and attendance data, migration adoption follows a **strictly non-destructive, opt-in strategy**:
1. **Disabled by Default:** `spring.flyway.enabled=false` is committed in `application.properties`. Normal local development, tests, and CI builds continue executing Hibernate `ddl-auto=update` without alteration.
2. **Authoritative Baseline (V1):** `V1__baseline_schema.sql` is derived directly from Hibernate 6's connectionless MySQL 8 schema export (`MySQLDialect`). It covers all 5 entity models: `departments`, `users`, `attendance_logs`, `leave_requests`, and `audit_logs`.
3. **Zero Blind DDL on Live Databases:** For existing populated databases, Flyway's `baseline-on-migrate=true` records version 1 as already applied, ensuring `V1` is **never executed against populated tables**. Only future versions (`V2+`) will execute incrementally.

---

## 2. Adoption Procedures

### Scenario A: Fresh Installation (Empty Database)

For new deployments or disposable test environments starting with an empty MySQL database:

1. Create an empty database:
   ```sql
   CREATE DATABASE college_attendance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
2. Enable Flyway and switch Hibernate to schema validation mode in your environment:
   ```properties
   spring.flyway.enabled=true
   spring.jpa.hibernate.ddl-auto=validate
   ```
   *(Or pass environment variables: `SPRING_FLYWAY_ENABLED=true` and `DDL_AUTO=validate`)*
3. Start the Spring Boot application:
   - Flyway detects the empty database and executes `V1__baseline_schema.sql`.
   - All 5 tables, unique constraints, foreign keys, and indexes are created.
   - `flyway_schema_history` records `V1` with status `SUCCESS`.
   - Spring Boot's `DataSeeder.java` populates initial departments and the designated developer account.

---

### Scenario B: Existing Installation (Populated Database)

For existing installations that evolved under Hibernate `ddl-auto=update`:

#### Step 1: Pre-Flight Verification & Snapshot (Mandatory)
Before altering any runtime configuration:
1. Create a full database backup snapshot using the disaster-recovery utility (see [BACKUP_AND_RESTORE.md](../BACKUP_AND_RESTORE.md)):
   ```bash
   # Windows
   database\backup.bat

   # Linux/macOS
   database/backup.sh
   ```
2. Verify that existing table structures match the entities. You can run the read-only `SchemaBaselineTest` against your local build to verify the entity baseline.

#### Step 2: Enable Flyway with Safe Baseline Settings
Configure the following properties in `application.properties` or environment variables:
```properties
# Enable Flyway
spring.flyway.enabled=true

# Safe Adoption Settings (committed in application.properties)
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
spring.flyway.baseline-description=Existing ddl-auto=update schema (pre-Flyway era)
spring.flyway.validate-on-migrate=true

# Switch Hibernate from managing schema to verifying schema
spring.jpa.hibernate.ddl-auto=validate
```

#### Step 3: Boot Spring Boot Backend
1. When Spring Boot boots:
   - Flyway inspects the database and sees existing tables (`users`, `departments`, etc.).
   - Because `baseline-on-migrate=true`, Flyway automatically creates the `flyway_schema_history` table and inserts a baseline record for `version = 1`.
   - **`V1__baseline_schema.sql` is NOT executed against the database.** Existing records, photos, and passwords remain 100% untouched.
   - Hibernate's `ddl-auto=validate` confirms that all JPA entity definitions match the live MySQL tables.
2. Subsequent schema updates must be created as `V2__<description>.sql`, `V3__<description>.sql`, etc. under `src/main/resources/db/migration/`.

---

## 3. MySQL DDL Auto-Commit & Failure Recovery

### Important MySQL Caveat
In MySQL, DDL statements (`CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`) cause an **implicit commit**. Unlike PostgreSQL, MySQL cannot roll back a failed multi-statement DDL migration inside a transaction.

### Failure Recovery Procedure
If a future migration (`V2+`) fails midway due to a syntax error or constraint conflict:
1. **Inspect Flyway State:**
   Query the history table:
   ```sql
   SELECT installed_rank, version, description, type, script, success FROM flyway_schema_history;
   ```
   A failed migration will show `success = 0`.
2. **Prevent Dirty State Startup:**
   Spring Boot will immediately refuse to start with `validate-on-migrate=true`.
3. **Resolution Steps:**
   - **Option 1 (Clean Restore):** Restore the database snapshot taken immediately before running the migration, fix the SQL script, and re-run.
   - **Option 2 (Manual Correction & History Repair):**
     1. Manually resolve or undo the partial DDL change in MySQL.
     2. Remove the failed row from `flyway_schema_history`:
        ```sql
        DELETE FROM flyway_schema_history WHERE version = 'X' AND success = 0;
        ```
     3. Fix the migration script in `src/main/resources/db/migration/`.
     4. Restart the application.

---

## 4. Test Isolation Guarantees

All automated migration and concurrency integration tests run against **disposable MySQL test instances** (e.g. `localhost:33071` database `college_attendance_ph31test`) and are gated behind system properties:
- `MySQLConcurrentPunchInIsolationTest`: Gated behind `-Dph31test.enabled=true`.
- `FlywayMigrationVerificationTest`: Gated behind `-Dflywayverify.enabled=true`.

**The production college database on port 3307 (`localhost:3307/college_attendance`) is strictly isolated and never connected to by any automated migration or integration test.**
