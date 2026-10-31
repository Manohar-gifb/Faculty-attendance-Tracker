@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: College Faculty Attendance Management System
:: Sanitized Development Export Utility (Windows)
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "DEFAULT_BACKUP_DIR=%SCRIPT_DIR%..\backups"

if "%BACKUP_DIR%"=="" set "BACKUP_DIR=%DEFAULT_BACKUP_DIR%"
if "%STAGE_HOST%"=="" set "STAGE_HOST=localhost"
if "%STAGE_PORT%"=="" set "STAGE_PORT=33071"
if "%STAGE_DB%"=="" set "STAGE_DB=college_attendance_stage"
if "%STAGE_USER%"=="" set "STAGE_USER=ph31testuser"

if "%STAGE_PASSWORD%"=="" (
    echo [ERROR] STAGE_PASSWORD environment variable is not set.
    echo Please set STAGE_PASSWORD for the disposable/staging database.
    exit /b 1
)

:: CRITICAL SAFETY GUARD: STRICT PROTECTION OF LIVE PRODUCTION DATABASE
if "%STAGE_PORT%"=="3307" (
    if /i "%STAGE_DB%"=="college_attendance" (
        echo [FATAL ERROR] Sanitization CANNOT be executed directly against the live production database!
        echo Live database protection active: localhost:3307/college_attendance is strictly protected.
        echo Please specify a separate disposable or staging database on port 33071.
        exit /b 1
    )
)

:: Ensure backup directory exists
if not exist "%BACKUP_DIR%" (
    mkdir "%BACKUP_DIR%" 2>nul
    if errorlevel 1 (
        echo [ERROR] Failed to create backup directory: %BACKUP_DIR%
        exit /b 1
    )
)

:: Generate timestamp
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value 2^>nul') do set "DATETIME=%%I"
if "%DATETIME%"=="" (
    for /f "tokens=1-4 delims=/:. " %%A in ("%DATE% %TIME%") do set "DATETIME=%%C%%B%%A_%%D"
)
set "TIMESTAMP=%DATETIME:~0,8%_%DATETIME:~8,6%"
set "EXPORT_FILE=%BACKUP_DIR%\%STAGE_DB%_sanitized_%TIMESTAMP%.sql"

if exist "%EXPORT_FILE%" (
    echo [ERROR] Target export file already exists: %EXPORT_FILE%
    exit /b 1
)

:: Locate binaries
set "MYSQL_CMD=mysql"
where mysql >nul 2>nul
if errorlevel 1 (
    if exist "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" (
        set "MYSQL_CMD=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    )
)

set "MYSQLDUMP_CMD=mysqldump"
where mysqldump >nul 2>nul
if errorlevel 1 (
    if exist "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" (
        set "MYSQLDUMP_CMD=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe"
    )
)

:: Temporary credentials config
set "TEMP_CNF=%TEMP%\mysql_san_%RANDOM%_%RANDOM%.cnf"
(
    echo [client]
    echo host=%STAGE_HOST%
    echo port=%STAGE_PORT%
    echo user=%STAGE_USER%
    echo password="%STAGE_PASSWORD%"
) > "%TEMP_CNF%"

echo [INFO] Applying sanitization rules on staging database '%STAGE_DB%' at %STAGE_HOST%:%STAGE_PORT%...
"%MYSQL_CMD%" --defaults-extra-file="%TEMP_CNF%" "%STAGE_DB%" < "%SCRIPT_DIR%sanitize-export.sql"
set "SAN_STATUS=%ERRORLEVEL%"

if not %SAN_STATUS%==0 (
    echo [ERROR] Sanitization script failed with exit code %SAN_STATUS%.
    if exist "%TEMP_CNF%" del /f /q "%TEMP_CNF%" >nul 2>nul
    exit /b %SAN_STATUS%
)

echo [INFO] Exporting sanitized snapshot to %EXPORT_FILE%...
"%MYSQLDUMP_CMD%" --defaults-extra-file="%TEMP_CNF%" ^
    --single-transaction ^
    --quick ^
    --default-character-set=utf8mb4 ^
    --set-gtid-purged=OFF ^
    "%STAGE_DB%" > "%EXPORT_FILE%"

set "DUMP_STATUS=%ERRORLEVEL%"
if exist "%TEMP_CNF%" del /f /q "%TEMP_CNF%" >nul 2>nul

if not %DUMP_STATUS%==0 (
    echo [ERROR] mysqldump failed with exit code %DUMP_STATUS%.
    if exist "%EXPORT_FILE%" del /f /q "%EXPORT_FILE%" >nul 2>nul
    exit /b %DUMP_STATUS%
)

echo [SUCCESS] Sanitized development export created successfully.
echo [WARNING] THIS FILE IS A SANITIZED DEVELOPMENT SNAPSHOT - NOT FOR DISASTER RECOVERY.
echo [INFO] File: %EXPORT_FILE%
exit /b 0
