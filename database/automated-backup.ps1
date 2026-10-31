# ============================================================
# College Faculty Attendance Management System (Faculty Sync Tracker)
# Unified Automated Production Backup Utility (Phase 9.1 Stage A)
# ============================================================
#
# Description:
#   Performs automated, non-destructive, timestamped backups of:
#     1. MySQL Relational Database (via Docker-native mysqldump & binary-safe docker cp)
#     2. Biometric Faculty Profile Photos (from active Docker storage volume)
#   Generates SHA-256 cryptographic manifests for both artifacts.
#   Includes concurrency lock protection and safe audit logging.
#
# Strict Safety Guarantees:
#   - Zero credential exposure in logs or process listings.
#   - 100% binary-safe SQL extraction (no PowerShell stdout text piping).
#   - Overwrite protection (aborts if target file exists).
#   - Cleans up only its own temporary staging files.
#   - Does NOT delete or prune existing backups.
#   - Does NOT modify database records or live photos.
# ============================================================

[CmdletBinding()]
param (
    [string]$BackupDir = "",
    [string]$DbContainer = "faculty_db",
    [string]$BackendContainer = "faculty_backend",
    [string]$DbName = "college_attendance"
)

$ErrorActionPreference = "Stop"

# Determine script and backup directories
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($BackupDir)) {
    $BackupDir = Join-Path -Path $scriptDir -ChildPath "..\backups"
}
$BackupDir = [System.IO.Path]::GetFullPath($BackupDir)

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

$auditLogFile = Join-Path -Path $BackupDir -ChildPath "backup_audit.log"
$lockFile = Join-Path -Path $BackupDir -ChildPath ".backup.lock"

function Write-Log {
    param (
        [string]$Message,
        [string]$Level = "INFO"
    )
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $logEntry = "[$ts] [$Level] $Message"
    Write-Host $logEntry
    Add-Content -Path $auditLogFile -Value $logEntry -Encoding UTF8
}

# ------------------------------------------------------------
# 1. Concurrency Mutex Lock Protection
# ------------------------------------------------------------
if (Test-Path $lockFile) {
    $lockContent = Get-Content -Path $lockFile -ErrorAction SilentlyContinue
    $lockPid = 0
    if ($lockContent -match 'PID:\s*(\d+)') {
        $lockPid = [int]$matches[1]
    }

    # Check if the process holding the lock is still alive
    $isProcessAlive = $false
    if ($lockPid -gt 0) {
        $proc = Get-Process -Id $lockPid -ErrorAction SilentlyContinue
        if ($proc) { $isProcessAlive = $true }
    }

    if ($isProcessAlive) {
        Write-Log "Another backup process (PID: $lockPid) is currently executing. Aborting to prevent overlapping runs." "WARN"
        exit 1
    } else {
        Write-Log "Stale lock file detected from previous run. Cleaning up lock." "WARN"
        Remove-Item -Path $lockFile -Force -ErrorAction SilentlyContinue
    }
}

$currentPid = $PID
"PID: $currentPid`nStarted: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content -Path $lockFile -Encoding ASCII

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$tempContainerDump = "/tmp/dump_${timestamp}.sql"
$tempPhotoStaging = Join-Path -Path $env:TEMP -ChildPath "photo_staging_${timestamp}"

try {
    Write-Log "============================================================"
    Write-Log "Starting Unified Automated Backup Session [Timestamp: $timestamp]"
    Write-Log "Target Backup Directory: $BackupDir"

    # ------------------------------------------------------------
    # 2. Database Backup (Docker-Native mysqldump)
    # ------------------------------------------------------------
    $dbBackupFile = Join-Path -Path $BackupDir -ChildPath "${DbName}_backup_${timestamp}.sql"
    $dbChecksumFile = Join-Path -Path $BackupDir -ChildPath "${DbName}_backup_${timestamp}.sql.sha256"

    if (Test-Path $dbBackupFile) {
        throw "Target database backup file already exists: $dbBackupFile. Overwrite protection active."
    }

    Write-Log "Verifying database container '$DbContainer' status..."
    $dbRunning = (docker ps -q -f "name=^/${DbContainer}$").Length -gt 0
    if (-not $dbRunning) {
        $dbRunning = (docker ps -q -f "name=${DbContainer}").Length -gt 0
    }

    if (-not $dbRunning) {
        throw "Database container '$DbContainer' is not running. Cannot execute Docker-native mysqldump."
    }

    Write-Log "Executing mysqldump inside container '$DbContainer' with transactional consistency..."
    # Execute mysqldump directly inside the container to avoid PowerShell stdout encoding corruption.
    # The output is written to a temporary file inside the container, then copied out using docker cp.
    $dumpCmd = "mysqldump -u `"`$MYSQL_USER`" -p`"`$MYSQL_PASSWORD`" " +
               "--single-transaction --quick --routines --triggers --events " +
               "--default-character-set=utf8mb4 --set-gtid-purged=OFF " +
               "`"`$MYSQL_DATABASE`" > $tempContainerDump"

    docker exec $DbContainer sh -c $dumpCmd
    if ($LASTEXITCODE -ne 0) {
        throw "mysqldump command failed inside container with exit code $LASTEXITCODE."
    }

    Write-Log "Copying raw SQL dump from container via binary-safe stream..."
    docker cp "${DbContainer}:${tempContainerDump}" $dbBackupFile
    if ($LASTEXITCODE -ne 0) {
        throw "docker cp failed to extract SQL dump with exit code $LASTEXITCODE."
    }

    # Verify file existence and non-zero size
    if (-not (Test-Path $dbBackupFile)) {
        throw "SQL backup file was not created at destination: $dbBackupFile"
    }
    $dbFileSize = (Get-Item $dbBackupFile).Length
    if ($dbFileSize -le 1024) {
        throw "SQL backup file is abnormally small ($dbFileSize bytes). Backup may be incomplete."
    }

    # Compute SHA-256 for the SQL dump
    $dbHash = (Get-FileHash -Path $dbBackupFile -Algorithm SHA256).Hash
    "$dbHash  $(Split-Path $dbBackupFile -Leaf)" | Set-Content -Path $dbChecksumFile -Encoding ASCII
    Write-Log "Database backup completed successfully: $(Split-Path $dbBackupFile -Leaf) ($dbFileSize bytes, SHA-256: $dbHash)" "SUCCESS"

    # ------------------------------------------------------------
    # 3. Biometric Photo Storage Backup
    # ------------------------------------------------------------
    $photoZipFile = Join-Path -Path $BackupDir -ChildPath "photos_archive_${timestamp}.zip"
    $photoManifestFile = Join-Path -Path $BackupDir -ChildPath "photos_manifest_${timestamp}.txt"

    if (Test-Path $photoZipFile) {
        throw "Target photo archive already exists: $photoZipFile. Overwrite protection active."
    }

    Write-Log "Verifying backend container '$BackendContainer' status for photo extraction..."
    $backendRunning = (docker ps -q -f "name=^/${BackendContainer}$").Length -gt 0
    if (-not $backendRunning) {
        $backendRunning = (docker ps -q -f "name=${BackendContainer}").Length -gt 0
    }

    New-Item -ItemType Directory -Path $tempPhotoStaging -Force | Out-Null

    if ($backendRunning) {
        Write-Log "Copying active profile photos from container '$BackendContainer'..."
        docker cp "${BackendContainer}:/var/faculty/uploads/profile-pictures/." $tempPhotoStaging
    } else {
        Write-Log "Backend container not running. Falling back to host storage 'C:\FacultyAttendance\uploads\profile-pictures'..." "WARN"
        if (Test-Path "C:\FacultyAttendance\uploads\profile-pictures") {
            Copy-Item -Path "C:\FacultyAttendance\uploads\profile-pictures\*" -Destination $tempPhotoStaging -Recurse -Force
        } else {
            throw "Profile picture directory not found on host or container."
        }
    }

    $photoFiles = Get-ChildItem -Path $tempPhotoStaging -File
    Write-Log "Found $($photoFiles.Count) profile photo(s) in active storage."

    # Generate SHA-256 manifest of individual photos
    $manifestLines = [System.Collections.Generic.List[string]]::new()
    foreach ($photo in $photoFiles) {
        $pHash = (Get-FileHash -Path $photo.FullName -Algorithm SHA256).Hash
        $manifestLines.Add("$pHash  $($photo.Name)")
    }
    $manifestLines | Set-Content -Path $photoManifestFile -Encoding UTF8
    Write-Log "Generated photo manifest: $(Split-Path $photoManifestFile -Leaf)"

    # Create ZIP archive
    Write-Log "Compressing photos into archive $(Split-Path $photoZipFile -Leaf)..."
    Compress-Archive -Path "$tempPhotoStaging\*" -DestinationPath $photoZipFile -CompressionLevel Optimal

    # Verify ZIP archive readability
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipTest = [System.IO.Compression.ZipFile]::OpenRead($photoZipFile)
    $entryCount = $zipTest.Entries.Count
    $zipTest.Dispose()

    if ($entryCount -lt $photoFiles.Count) {
        throw "Photo archive verification failed: Archive contains $entryCount entries, expected $($photoFiles.Count)."
    }

    $photoZipSize = (Get-Item $photoZipFile).Length
    $zipHash = (Get-FileHash -Path $photoZipFile -Algorithm SHA256).Hash
    Write-Log "Photo archive created and verified: $(Split-Path $photoZipFile -Leaf) ($entryCount files, $photoZipSize bytes, SHA-256: $zipHash)" "SUCCESS"

    Write-Log "Unified Automated Backup Session completed successfully in all phases." "SUCCESS"
    Write-Log "============================================================"
    exit 0

} catch {
    Write-Log "BACKUP FAILED: $($_.Exception.Message)" "ERROR"
    # Clean up incomplete target files if any were left in error
    if (Test-Path $dbBackupFile) {
        if ((Get-Item $dbBackupFile).Length -le 1024) {
            Remove-Item -Path $dbBackupFile -Force -ErrorAction SilentlyContinue
        }
    }
    exit 1
} finally {
    # Clean up container temporary dump file
    if ($dbRunning) {
        docker exec $DbContainer rm -f $tempContainerDump 2>$null
    }
    # Clean up host temporary staging directory
    if (Test-Path $tempPhotoStaging) {
        Remove-Item -Path $tempPhotoStaging -Recurse -Force -ErrorAction SilentlyContinue
    }
    # Release mutex lock
    if (Test-Path $lockFile) {
        Remove-Item -Path $lockFile -Force -ErrorAction SilentlyContinue
    }
}
