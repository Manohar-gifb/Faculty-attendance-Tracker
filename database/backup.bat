@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: College Faculty Attendance Management System
:: Disaster-Recovery Full Database Backup Utility (Windows)
:: ============================================================

set "SCRIPT_DIR=%~dp0"
set "DEFAULT_BACKUP_DIR=%SCRIPT_DIR%..\backups"

if "%BACKUP_DIR%"=="" set "BACKUP_DIR=%DEFAULT_BACKUP_DIR%"
if "%DB_HOST%"=="" set "DB_HOST=localhost"
if "%DB_PORT%"=="" set "DB_PORT=3307"
if "%DB_NAME%"=="" set "DB_NAME=college_attendance"
if "%DB_USER%"=="" set "DB_USER=root"

if "%DB_PASSWORD%"=="" (
    echo [ERROR] DB_PASSWORD environment variable is not set.
    echo Please set DB_PASSWORD securely before running this backup.
    exit /b 1
)

:: Ensure backup directory exists
if not exist "%BACKUP_DIR%" (
    mkdir "%BACKUP_DIR%" 2>nul
    if errorlevel 1 (
        echo [ERROR] Failed to create backup directory: %BACKUP_DIR%
        exit /b 1
    )
)

:: Generate timestamped filename: YYYYMMDD_HHMMSS
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value 2^>nul') do set "DATETIME=%%I"
if "%DATETIME%"=="" (
    for /f "tokens=1-4 delims=/:. " %%A in ("%DATE% %TIME%") do set "DATETIME=%%C%%B%%A_%%D"
)
set "TIMESTAMP=%DATETIME:~0,8%_%DATETIME:~8,6%"
set "BACKUP_FILE=%BACKUP_DIR%\%DB_NAME%_backup_%TIMESTAMP%.sql"

if exist "%BACKUP_FILE%" (
    echo [ERROR] Target backup file already exists: %BACKUP_FILE%
    echo Overwrite protection active. Aborting to prevent data loss.
    exit /b 1
)

:: Locate mysqldump executable
set "MYSQLDUMP_CMD=mysqldump"
where mysqldump >nul 2>nul
if errorlevel 1 (
    if exist "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" (
        set "MYSQLDUMP_CMD=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe"
    ) else (
        echo [ERROR] mysqldump command not found in PATH or standard installation directory.
        exit /b 1
    )
)

:: Create secure temporary options file to avoid exposing credentials on the command line
set "TEMP_CNF=%TEMP%\mysql_dr_%RANDOM%_%RANDOM%.cnf"
(
    echo [client]
    echo host=%DB_HOST%
    echo port=%DB_PORT%
    echo user=%DB_USER%
    echo password="%DB_PASSWORD%"
) > "%TEMP_CNF%"

echo [INFO] Starting full database backup for '%DB_NAME%' on %DB_HOST%:%DB_PORT%...
echo [INFO] Output target: %BACKUP_FILE%

"%MYSQLDUMP_CMD%" --defaults-extra-file="%TEMP_CNF%" ^
    --single-transaction ^
    --routines ^
    --triggers ^
    --events ^
    --quick ^
    --default-character-set=utf8mb4 ^
    --set-gtid-purged=OFF ^
    "%DB_NAME%" > "%BACKUP_FILE%"

set "DUMP_STATUS=%ERRORLEVEL%"

:: Guarantee immediate removal of temporary credential file
if exist "%TEMP_CNF%" del /f /q "%TEMP_CNF%" >nul 2>nul

if not %DUMP_STATUS%==0 (
    echo [ERROR] mysqldump failed with exit code %DUMP_STATUS%.
    if exist "%BACKUP_FILE%" del /f /q "%BACKUP_FILE%" >nul 2>nul
    exit /b %DUMP_STATUS%
)

:: Validate file creation and non-zero size
if not exist "%BACKUP_FILE%" (
    echo [ERROR] Backup file was not created.
    exit /b 1
)

for %%F in ("%BACKUP_FILE%") do set "FILE_SIZE=%%~zF"
if %FILE_SIZE% LEQ 0 (
    echo [ERROR] Backup file is empty ^(0 bytes^).
    del /f /q "%BACKUP_FILE%" >nul 2>nul
    exit /b 1
)

echo [SUCCESS] Full database backup completed successfully.
echo [INFO] File: %BACKUP_FILE% ^(Size: %FILE_SIZE% bytes^)
exit /b 0
