@echo off
setlocal enabledelayedexpansion
title Face Verification Service (Port 5001)

echo ============================================================
echo   College Faculty Attendance - Face Verification Service
echo ============================================================

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%face_verification"

set SERVICE_PORT=5001
if defined PORT set SERVICE_PORT=%PORT%

rem 1. Check if port is already occupied
echo Checking port %SERVICE_PORT% availability...
netstat -ano | findstr /R /C:":%SERVICE_PORT% .*LISTENING" >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [ERROR] Port %SERVICE_PORT% is already occupied by another process.
    echo Face Verification Service cannot bind to port %SERVICE_PORT%.
    echo Please stop the existing process or service before starting.
    pause
    exit /b 1
)

rem 2. Locate Python executable
set PY_EXE=
if exist "venv\Scripts\python.exe" (
    set "PY_EXE=venv\Scripts\python.exe"
    echo Using local virtual environment: venv
) else if exist ".venv\Scripts\python.exe" (
    set "PY_EXE=.venv\Scripts\python.exe"
    echo Using local virtual environment: .venv
) else (
    where python >nul 2>nul
    if !ERRORLEVEL! EQU 0 (
        set "PY_EXE=python"
        echo Using system PATH Python...
    ) else if exist "%LOCALAPPDATA%\Programs\Python\Python310\python.exe" (
        set "PY_EXE=%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
        echo Using local Python 3.10...
    ) else if exist "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" (
        set "PY_EXE=%LOCALAPPDATA%\Programs\Python\Python311\python.exe"
        echo Using local Python 3.11...
    )
)

if "%PY_EXE%"=="" (
    echo [ERROR] Python 3 was not found on your system.
    echo Please install Python 3.10 or 3.11 from https://www.python.org/downloads/
    echo and ensure it is added to your PATH.
    pause
    exit /b 1
)

rem 3. Verify required dependencies without silently mutating environment
echo Checking Python dependencies...
"%PY_EXE%" -c "import flask, flask_cors, PIL, numpy, face_recognition" >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] One or more required Python dependencies are missing.
    echo Please install dependencies by running:
    echo.
    echo   cd face_verification
    echo   "%PY_EXE%" -m pip install -r requirements.txt
    echo.
    echo If dlib installation fails on Windows due to missing C++ build tools:
    echo Consult face_verification\README.md for platform wheel options such as dlib-bin.
    pause
    exit /b 1
)

rem 4. Safe secret propagation (never echo secret value)
if defined INTERNAL_SERVICE_SECRET (
    echo Inter-service security secret: [Configured from environment]
) else (
    echo [WARNING] INTERNAL_SERVICE_SECRET is not set in environment.
)

echo Starting Face Verification Service on 127.0.0.1:%SERVICE_PORT%...
echo Press Ctrl+C to terminate the service.
echo ============================================================

"%PY_EXE%" face_service.py

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Face Verification Service terminated with error code %ERRORLEVEL%.
    pause
)
