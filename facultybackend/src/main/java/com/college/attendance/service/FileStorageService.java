package com.college.attendance.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Service for securely persisting and resolving uploaded profile pictures
 * in a dedicated persistent directory outside of Tomcat's temporary folder.
 */
@Service
@Slf4j
public class FileStorageService {

    public static final String PROFILE_PICTURES_URL_PREFIX = "/uploads/profile-pictures/";

    private static volatile Path staticUploadDir;

    @Value("${app.upload.dir:${UPLOAD_DIR:C:/FacultyAttendance/uploads/profile-pictures}}")
    private String configuredUploadDir;

    private Path uploadDirectory;

    @PostConstruct
    public void init() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String pathStr = configuredUploadDir != null && !configuredUploadDir.isBlank()
                ? configuredUploadDir
                : "C:/FacultyAttendance/uploads/profile-pictures";

        // If deployed on non-Windows OS (Linux/Docker) and path contains Windows drive letter, fallback
        if (!os.contains("win") && pathStr.matches("^[a-zA-Z]:[/\\\\].*")) {
            String envDir = System.getenv("UPLOAD_DIR");
            if (envDir != null && !envDir.isBlank()) {
                pathStr = envDir;
            } else {
                pathStr = "/var/faculty/uploads/profile-pictures";
            }
        }

        this.uploadDirectory = Paths.get(pathStr).toAbsolutePath().normalize();
        staticUploadDir = this.uploadDirectory;

        try {
            Files.createDirectories(this.uploadDirectory);
            this.uploadDirectory.toFile().setReadable(true, false);
            this.uploadDirectory.toFile().setWritable(true, false);
            this.uploadDirectory.toFile().setExecutable(true, false);
            log.info("Initialized profile picture persistent storage at: {}", this.uploadDirectory);
        } catch (IOException e) {
            log.error("Could not initialize upload directory at {}: {}", this.uploadDirectory, e.getMessage());
            throw new IllegalStateException("Could not create profile picture directory: " + this.uploadDirectory, e);
        }
    }

    public Path getUploadDirectory() {
        if (this.uploadDirectory == null) {
            init();
        }
        return this.uploadDirectory;
    }

    public static Path getStaticUploadDirectory() {
        if (staticUploadDir != null) {
            return staticUploadDir;
        }
        return Paths.get("C:/FacultyAttendance/uploads/profile-pictures").toAbsolutePath().normalize();
    }

    /**
     * Safely store an uploaded profile picture to disk.
     * Uses Files.copy with an InputStream rather than transferTo(File)
     * to avoid relative path resolution against Tomcat work directory.
     *
     * @param file the uploaded MultipartFile
     * @param filename the safe, server-generated filename
     * @return relative URL reference to be saved in the database
     * @throws IOException if saving fails
     */
    public String storeProfilePicture(MultipartFile file, String filename) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Cannot store empty file.");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be empty.");
        }

        // Path traversal guard
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new SecurityException("Filename contains invalid path characters: " + filename);
        }

        Path dir = getUploadDirectory();
        Files.createDirectories(dir);

        Path destination = dir.resolve(filename).normalize();
        if (!destination.startsWith(dir) || !destination.getParent().equals(dir)) {
            throw new SecurityException("Security violation: path traversal attempt for " + filename);
        }

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            destination.toFile().setReadable(true, false);
            destination.toFile().setWritable(true, false);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
            }
            log.error("Failed to write profile picture {}: {}", destination, e.getMessage());
            throw e;
        }

        // Verify successful write and non-zero size
        if (!Files.exists(destination) || Files.size(destination) == 0) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
            }
            throw new IOException("Failed to save image file: file is missing or zero bytes after write.");
        }

        log.debug("Successfully saved profile picture to: {}", destination);
        return PROFILE_PICTURES_URL_PREFIX + filename;
    }

    /**
     * Resolves the real filesystem path for a relative URL or filename.
     */
    public Path resolvePath(String relativeUrlOrFilename) {
        if (relativeUrlOrFilename == null || relativeUrlOrFilename.isBlank()) {
            return null;
        }

        String filename = relativeUrlOrFilename;
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new SecurityException("Invalid filename: " + filename);
        }

        Path primary = getUploadDirectory().resolve(filename).normalize();
        if (Files.exists(primary)) {
            return primary;
        }

        // Fallback to legacy project directories
        Path fallbackBackend = Paths.get("facultybackend", "uploads", "profile-pictures", filename).toAbsolutePath().normalize();
        if (Files.exists(fallbackBackend)) {
            return fallbackBackend;
        }

        Path fallbackRoot = Paths.get("uploads", "profile-pictures", filename).toAbsolutePath().normalize();
        if (Files.exists(fallbackRoot)) {
            return fallbackRoot;
        }

        return primary;
    }

    /**
     * Load an image file as a Spring Resource.
     */
    public Resource loadAsResource(String filename) {
        Path path = resolvePath(filename);
        if (path != null && Files.exists(path) && Files.isReadable(path)) {
            try {
                return new UrlResource(path.toUri());
            } catch (MalformedURLException e) {
                log.warn("Malformed URL for file {}: {}", path, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Delete a previously stored profile picture, identified by the stored URL
     * or bare filename. Used exclusively by the photo lifecycle cleanup
     * (FILE-04). Applies the same traversal and containment guards as
     * {@link #storeProfilePicture}: only files that resolve DIRECTLY inside
     * the configured upload directory can ever be touched. Returns true when
     * the file existed and was deleted.
     */
    public boolean deleteStoredProfilePicture(String filenameOrUrl) throws IOException {
        if (filenameOrUrl == null || filenameOrUrl.isBlank()) {
            return false;
        }

        String filename = filenameOrUrl.replace('\\', '/');
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        if (filename.isBlank() || filename.contains("..")) {
            throw new SecurityException("Filename contains invalid path characters: " + filenameOrUrl);
        }

        Path dir = getUploadDirectory();
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir) || !target.getParent().equals(dir)) {
            throw new SecurityException("Security violation: refusing to delete outside " + dir + " for " + filenameOrUrl);
        }

        boolean deleted = Files.deleteIfExists(target);
        if (deleted) {
            log.info("Deleted stored profile picture: {}", target);
        }
        return deleted;
    }
}
