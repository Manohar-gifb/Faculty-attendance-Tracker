@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: College Faculty Attendance Management System
:: Safe Database Restore Utility (Windows)
:: ============================================================

set "BACKUP_FILE=%~1"
if "%BACKUP_FILE%"=="" (
    echo [ERROR] Usage: database\restore.bat ^<path_to_backup_file.sql^>
    echo An explicit backup file argument is required.
    exit /b 1
)

if not exist "%BACKUP_FILE%" (
    echo [ERROR] Backup file does not exist: %BACKUP_FILE%
    exit /b 1
)

for %%F in ("%BACKUP_FILE%") do set "FILE_SIZE=%%~zF"
if %FILE_SIZE% LEQ 0 (
    echo [ERROR] Backup file is empty ^(0 bytes^). Refusing to restore an empty file.
    exit /b 1
)

if "%RESTORE_DB%"=="" (
    echo [ERROR] RESTORE_DB environment variable is not set.
    echo An explicit target database name is required to prevent accidental overwrites.
    exit /b 1
)

if "%RESTORE_HOST%"=="" set "RESTORE_HOST=localhost"
if "%RESTORE_PORT%"=="" set "RESTORE_PORT=33071"
if "%RESTORE_USER%"=="" set "RESTORE_USER=ph31testuser"

if "%RESTORE_PASSWORD%"=="" (
    echo [ERROR] RESTORE_PASSWORD environment variable is not set.
    echo Please set RESTORE_PASSWORD for target database %RESTORE_DB%.
    exit /b 1
)

:: CRITICAL SAFETY GUARD: STRICT PROTECTION OF LIVE PRODUCTION DATABASE
if "%RESTORE_PORT%"=="3307" (
    if /i "%RESTORE_DB%"=="college_attendance" (
        echo [FATAL ERROR] Restoring directly into the live production database 'localhost:3307/college_attendance' is strictly prohibited!
        echo Live database protection active: this utility only permits restoring into isolated, disposable, or disaster-recovery test databases.
        exit /b 1
    )
)

:: Locate mysql binary
set "MYSQL_CMD=mysql"
where mysql >nul 2>nul
if errorlevel 1 (
    if exist "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" (
        set "MYSQL_CMD=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    ) else (
        echo [ERROR] mysql command not found in PATH or standard installation directory.
        exit /b 1
    )
)

:: Create secure temporary options file
set "TEMP_CNF=%TEMP%\mysql_rst_%RANDOM%_%RANDOM%.cnf"
(
    echo [client]
    echo host=%RESTORE_HOST%
    echo port=%RESTORE_PORT%
    echo user=%RESTORE_USER%
    echo password="%RESTORE_PASSWORD%"
) > "%TEMP_CNF%"

echo [INFO] Verifying target database connectivity on %RESTORE_HOST%:%RESTORE_PORT%...
"%MYSQL_CMD%" --defaults-extra-file="%TEMP_CNF%" -e "SELECT 1;" "%RESTORE_DB%" >nul 2>nul
set "CONN_STATUS=%ERRORLEVEL%"

if not %CONN_STATUS%==0 (
    echo [ERROR] Cannot connect to target database '%RESTORE_DB%' on %RESTORE_HOST%:%RESTORE_PORT%.
    echo Please ensure the target database exists and credentials are valid.
    if exist "%TEMP_CNF%" del /f /q "%TEMP_CNF%" >nul 2>nul
    exit /b %CONN_STATUS%
)

echo [INFO] Restoring '%BACKUP_FILE%' into '%RESTORE_DB%' on %RESTORE_HOST%:%RESTORE_PORT%...
"%MYSQL_CMD%" --defaults-extra-file="%TEMP_CNF%" "%RESTORE_DB%" < "%BACKUP_FILE%"
set "RESTORE_STATUS=%ERRORLEVEL%"

if exist "%TEMP_CNF%" del /f /q "%TEMP_CNF%" >nul 2>nul

if not %RESTORE_STATUS%==0 (
    echo [ERROR] Restore operation failed with exit code %RESTORE_STATUS%.
    exit /b %RESTORE_STATUS%
)

echo [SUCCESS] Database restoration completed successfully into '%RESTORE_DB%'.
exit /b 0
