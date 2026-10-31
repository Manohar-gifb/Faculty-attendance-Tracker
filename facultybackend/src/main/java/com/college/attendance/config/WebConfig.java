package com.college.attendance.config;

import com.college.attendance.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves the uploaded profile pictures from disk.
 *
 * Resolves static resources from the configured persistent upload directory
 * (e.g. C:/FacultyAttendance/uploads/profile-pictures on Windows or UPLOAD_DIR on Linux)
 * as well as legacy project upload paths so existing photos remain accessible.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    public static final String PROFILE_PICTURES_SUBDIR = "profile-pictures";

    private final FileStorageService fileStorageService;

    public static Path profilePicturesDir() {
        return FileStorageService.getStaticUploadDirectory();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = fileStorageService.getUploadDirectory();
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
            // Spring will surface a clearer error if the directory cannot be created on demand.
        }

        String primaryLocation = dir.toUri().toString();
        if (!primaryLocation.endsWith("/")) {
            primaryLocation += "/";
        }

        Path legacyBackend = Paths.get("facultybackend", "uploads", PROFILE_PICTURES_SUBDIR).toAbsolutePath().normalize();
        String legacyBackendLoc = legacyBackend.toUri().toString();
        if (!legacyBackendLoc.endsWith("/")) {
            legacyBackendLoc += "/";
        }

        Path legacyRoot = Paths.get("uploads", PROFILE_PICTURES_SUBDIR).toAbsolutePath().normalize();
        String legacyRootLoc = legacyRoot.toUri().toString();
        if (!legacyRootLoc.endsWith("/")) {
            legacyRootLoc += "/";
        }

        // An absolute location ending in "/" (required by ResourceHandlerRegistry's appender so
        // the per-request subpath is appended instead of replacing the last segment).
        registry.addResourceHandler("/uploads/profile-pictures/**")
                .addResourceLocations(primaryLocation, legacyBackendLoc, legacyRootLoc)
                .setCachePeriod(3600);
    }
}
