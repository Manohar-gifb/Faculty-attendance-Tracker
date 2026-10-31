package com.college.attendance.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side validation of uploaded profile images.
 *
 * Also used by the Developer "create account" photo upload so both paths
 * enforce identical rules:
 *   - whitelisted extensions (.jpg / .jpeg / .png / .webp) — the original
 *     filename is client-controlled and must never be trusted for the
 *     stored extension;
 *   - declared MIME type must be in the strict image whitelist
 *     (image/jpeg, image/jpg, image/png, image/webp);
 *   - the actual file CONTENT must start with the magic bytes of the declared
 *     image format (guards against HTML/SVG/script payloads disguised with an
 *     image Content-Type, the stored-XSS vector under public /uploads/**);
 *   - maximum 5 MB;
 *   - server-generated UUID filenames (no path traversal, no collisions).
 */
public final class ImageFileValidator {

    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    /**
     * FILE-01 declared MIME whitelist. `image/jpg` is accepted defensively:
     * it is a non-standard spelling some clients still send for JPEG.
     */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private ImageFileValidator() {
    }

    /**
     * Validate an uploaded image; throws IllegalArgumentException with a
     * user-safe message when the file must be rejected.
     * Returns the whitelisted, lower-case extension to store the file with
     * (".jpg", ".jpeg", ".png" or ".webp").
     */
    public static String validatedExtension(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an image file.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(normalizeMimeType(contentType))) {
            throw new IllegalArgumentException("Unsupported file type. Please upload a JPEG, PNG or WebP image.");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!isAllowedExtension(extension)) {
            throw new IllegalArgumentException("Unsupported image format. Please upload a JPEG, PNG or WebP image.");
        }

        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("File too large. Maximum size is 5 MB.");
        }

        try {
            if (!matchesMagicBytes(file, extension)) {
                throw new IllegalArgumentException("This file is not a valid image. Please upload a real JPEG, PNG or WebP photo.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file. Please try again.");
        }

        return extension;
    }

    /** Generate a safe storage filename: server-controlled UUID + whitelisted extension. */
    public static String safeFilename(String extension) {
        return UUID.randomUUID() + extension;
    }

    private static String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return "";
        return originalFilename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Trim + lowercase a declared Content-Type before whitelist comparison. */
    private static String normalizeMimeType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowedExtension(String extension) {
        return extension.equals(".jpg") || extension.equals(".jpeg")
                || extension.equals(".png") || extension.equals(".webp");
    }

    /**
     * Content sniffing against the image formats we allow:
     *   JPEG  : FF D8 FF
     *   PNG   : 89 'P' 'N' 'G' 0D 0A 1A 0A
     *   WebP  : 'R' 'I' 'F' 'F' .... 'W' 'E' 'B' 'P'
     */
    private static boolean matchesMagicBytes(MultipartFile file, String extension) throws IOException {
        byte[] header = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(header, 0, header.length);
        }
        if (read < 8) {
            return false;
        }

        switch (extension) {
            case ".jpg":
            case ".jpeg":
                return unsigned(header[0]) == 0xFF && unsigned(header[1]) == 0xD8 && unsigned(header[2]) == 0xFF;
            case ".png":
                return unsigned(header[0]) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                        && unsigned(header[4]) == 0x0D && unsigned(header[5]) == 0x0A
                        && unsigned(header[6]) == 0x1A && unsigned(header[7]) == 0x0A;
            case ".webp":
                return read >= 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            default:
                return false;
        }
    }

    private static int unsigned(byte b) {
        return b & 0xFF;
    }
}
