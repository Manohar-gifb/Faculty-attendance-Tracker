package com.college.attendance;

import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.support.StubEmailConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.college.attendance.service.FileStorageService;
import com.college.attendance.service.PhotoCleanupService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for profile picture upload & display:
 *
 *  1. Upload used to "succeed" (DB row updated) but /uploads/** returned 404
 *     because Spring Boot had no resource handler for the stored files.
 *  2. The original filename/extension was client-controlled (.html/.svg were
 *     accepted), enabling stored-script payloads under public /uploads/**.
 *
 * These tests cover: valid upload + DB association + GET of the returned URL;
 * disallowed extension; content that is not a real image; oversized file;
 * verification that the profile endpoint returns the same URL after reload.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class ProfilePictureUploadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private PhotoCleanupService photoCleanupService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String email;
    private final String password = "Faculty@123";

    @BeforeEach
    void setUp() {
        StubEmailConfiguration.StubEmailService.reset();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Department department = departmentRepository.findByCode("PHTC" + suffix)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("PhotoTest Dept " + suffix)
                        .code("PHTC" + suffix)
                        .isActive(true)
                        .build()));

        email = "photo." + suffix + "@test.local";
        userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.builder()
                        .employeeId("PHTF" + suffix)
                        .firstName("Photo")
                        .lastName("Test")
                        .email(email)
                        .passwordHash(passwordEncoder.encode(password))
                        .role(Role.FACULTY)
                        .department(department)
                        .isActive(true)
                        .firstLogin(false)
                        .build()));
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MockMultipartFile jpegFile() {
        // Real JPEG signature (FF D8 FF …) followed by nominal payload
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3, 4, 5};
        return new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpeg);
    }

    // =========================================================
    // §1 valid JPEG upload -> associated with principal + served URL
    // =========================================================

    @Test
    void validJpegUploadStoresFileServesUrlAndAssociatesWithUser() throws Exception {
        String token = login();

        MvcResult uploadResult = mockMvc.perform(multipart("/profile/picture")
                        .file(jpegFile())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePictureUrl").isNotEmpty())
                .andReturn();

        String url = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("profilePictureUrl").asText();
        assertTrue(url.startsWith("/uploads/profile-pictures/"), "URL must be the public static path: " + url);
        // server-generated filename, never the client's original name
        assertEquals(".jpg", url.substring(url.length() - 4));

        // The stored image must actually be retrievable — the root cause of the
        // missing picture was that /uploads/** was never served (404).
        mockMvc.perform(get(url))
                .andDo(result -> System.out.println("[DEBUG] GET " + url + " -> "
                        + result.getResponse().getStatus()
                        + " handled=? " + result.getHandler())
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));

        // Verify the file was physically saved in the configured storage directory
        String filename = url.substring("/uploads/profile-pictures/".length());
        java.nio.file.Path savedFile = fileStorageService.getUploadDirectory().resolve(filename);
        assertTrue(java.nio.file.Files.exists(savedFile), "File must physically exist in configured directory: " + savedFile);
        assertTrue(java.nio.file.Files.size(savedFile) > 0, "Saved file must not be empty");

        // Profile endpoint returns the same URL (survives reload / re-login)
        MvcResult profileResult = mockMvc.perform(get("/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(url,
                objectMapper.readTree(profileResult.getResponse().getContentAsString())
                        .get("profilePictureUrl").asText());
    }

    // =========================================================
    // §2 disallowed extension rejected
    // =========================================================

    @Test
    void disallowedExtensionIsRejected() throws Exception {
        String token = login();

        MockMultipartFile svg = new MockMultipartFile("file", "profile.svg", "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg'/><script>alert(1)</script>".getBytes());

        mockMvc.perform(multipart("/profile/picture")
                        .file(svg)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================
    // §3 content that is not a real image is rejected (magic bytes)
    // =========================================================

    @Test
    void fakeImageContentIsRejected() throws Exception {
        String token = login();

        MockMultipartFile fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                "<html><script>cookie-stealer()</script></html>".getBytes());

        mockMvc.perform(multipart("/profile/picture")
                        .file(fake)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================
    // §4 empty file rejected
    // =========================================================

    @Test
    void emptyFileIsRejected() throws Exception {
        String token = login();

        mockMvc.perform(multipart("/profile/picture")
                        .file(new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §5 WebP is accepted (whitelisted format)
    // =========================================================

    @Test
    void webpUploadIsAccepted() throws Exception {
        String token = login();

        byte[] riff = new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0, 1, 2
        };
        MockMultipartFile webp = new MockMultipartFile("file", "avatar.webp", "image/webp", riff);

        MvcResult uploadResult = mockMvc.perform(multipart("/profile/picture")
                        .file(webp)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePictureUrl").isNotEmpty())
                .andReturn();

        String url = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("profilePictureUrl").asText();
        assertTrue(url.endsWith(".webp"));

        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"));
    }

    // =========================================================
    // §6 failed upload preserves the previous photo on profile
    // =========================================================

    @Test
    void failedUploadPreservesPreviousPhotoOnProfile() throws Exception {
        String token = login();

        // 1. Initial valid upload
        MvcResult firstUpload = mockMvc.perform(multipart("/profile/picture")
                        .file(jpegFile())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String initialUrl = objectMapper.readTree(firstUpload.getResponse().getContentAsString())
                .get("profilePictureUrl").asText();

        // 2. Attempt overwrite by faculty -> rejected as forbidden (registered face protection)
        MockMultipartFile badFile = new MockMultipartFile("file", "fake.jpg", "image/jpeg", "bad-payload".getBytes());
        mockMvc.perform(multipart("/profile/picture")
                        .file(badFile)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // 3. Verify user's profile picture URL is preserved
        MvcResult profileResult = mockMvc.perform(get("/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String currentUrl = objectMapper.readTree(profileResult.getResponse().getContentAsString())
                .get("profilePictureUrl").asText();
        assertEquals(initialUrl, currentUrl, "Failed upload must preserve the user's previous photo");
    }

    @Test
    void developerFailedPhotoUpdatePreservesPreviousPhoto() throws Exception {
        // Login as developer
        MvcResult devLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"Dev@12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String devToken = objectMapper.readTree(devLogin.getResponse().getContentAsString()).get("token").asText();

        // 1. Initial upload
        MvcResult first = mockMvc.perform(multipart("/profile/picture")
                        .file(jpegFile())
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        String devUrl = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("profilePictureUrl").asText();

        // 2. Attempt invalid image upload as developer (allowed to overwrite, but fails image validation)
        MockMultipartFile fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not-an-image".getBytes());
        mockMvc.perform(multipart("/profile/picture")
                        .file(fake)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isBadRequest());

        // 3. Verify previous photo preserved
        MvcResult devProfile = mockMvc.perform(get("/profile")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(devUrl, objectMapper.readTree(devProfile.getResponse().getContentAsString())
                .get("profilePictureUrl").asText());
    }

    // =========================================================
    // §7 Path traversal rejection in FileStorageService
    // =========================================================

    @Test
    void fileStorageServicePreventsPathTraversal() {
        MockMultipartFile file = jpegFile();
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class, () -> {
            fileStorageService.storeProfilePicture(file, "../traversal.jpg");
        });
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class, () -> {
            fileStorageService.storeProfilePicture(file, "..\\traversal.jpg");
        });
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class, () -> {
            fileStorageService.storeProfilePicture(file, "sub/traversal.jpg");
        });
    }

    // =========================================================
    // §8 FILE-01: upload larger than the 5 MB limit is rejected
    // =========================================================

    @Test
    void oversizedUploadIsRejectedAndLeavesNoFile() throws Exception {
        String token = login();

        long filesBefore;
        try (var stream = java.nio.file.Files.list(fileStorageService.getUploadDirectory())) {
            filesBefore = stream.count();
        }

        // 5 MB + 1 byte payload carrying a real JPEG signature — the size gate
        // (not the magic-byte gate) must reject it.
        byte[] payload = new byte[5 * 1024 * 1024 + 1];
        payload[0] = (byte) 0xFF;
        payload[1] = (byte) 0xD8;
        payload[2] = (byte) 0xFF;
        MockMultipartFile oversized = new MockMultipartFile("file", "big.jpg", "image/jpeg", payload);

        mockMvc.perform(multipart("/profile/picture")
                        .file(oversized)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File too large. Maximum size is 5 MB."));

        long filesAfter;
        try (var stream = java.nio.file.Files.list(fileStorageService.getUploadDirectory())) {
            filesAfter = stream.count();
        }
        assertEquals(filesBefore, filesAfter, "A rejected oversized upload must not leave a partial file in storage");
    }

    // =========================================================
    // §9 FILE-01: declared MIME outside the strict whitelist rejected
    // =========================================================

    @Test
    void declaredMimeOutsideStrictWhitelistIsRejected() throws Exception {
        String token = login();

        // Real JPEG bytes, but declared as image/gif — no longer admitted by
        // the loose image/* prefix check (FILE-01 whitelist).
        MockMultipartFile gif = new MockMultipartFile("file", "avatar.jpg", "image/gif", jpegFile().getBytes());

        mockMvc.perform(multipart("/profile/picture")
                        .file(gif)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported file type. Please upload a JPEG, PNG or WebP image."));
    }

    // =========================================================
    // §10 FILE-01: extension/signature mismatch rejected
    // (JPEG magic stored under a .png name)
    // =========================================================

    @Test
    void extensionSignatureMismatchIsRejected() throws Exception {
        String token = login();

        MockMultipartFile mismatch = new MockMultipartFile("file", "photo.png", "image/png",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3});

        mockMvc.perform(multipart("/profile/picture")
                        .file(mismatch)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This file is not a valid image. Please upload a real JPEG, PNG or WebP photo."));
    }

    // =========================================================
    // §11 FILE-02: new self-service filenames are 36-char UUIDs
    // =========================================================

    @Test
    void selfServiceUploadUsesPureUuidFilename() throws Exception {
        String token = login();

        MvcResult uploadResult = mockMvc.perform(multipart("/profile/picture")
                        .file(jpegFile())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String url = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("profilePictureUrl").asText();
        String filename = url.substring("/uploads/profile-pictures/".length());

        assertTrue(filename.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png|webp)"),
                "Self-service uploads must use a full server-generated UUID filename: " + filename);
    }

    // =========================================================
    // §12 FILE-02: developer uploads keep user_<id>_ prefix but use a
    // full 36-char UUID random segment (collision-proof, no overwrite)
    // =========================================================

    @Test
    void developerUploadUsesFullUuidSuffixAfterUserIdPrefix() throws Exception {
        MvcResult devLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"Dev@12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String devToken = objectMapper.readTree(devLogin.getResponse().getContentAsString()).get("token").asText();

        // The user_<id>_<uuid> naming lives in DeveloperService.saveProfilePhoto,
        // exposed through POST /developer/accounts/{id}/photo (NOT /profile/picture,
        // which always uses pure UUID names). Upload on the developer's own account.
        MvcResult profileResult = mockMvc.perform(get("/profile")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        String accountId = objectMapper.readTree(profileResult.getResponse().getContentAsString())
                .get("id").asText();

        MvcResult uploadResult = mockMvc.perform(multipart("/developer/accounts/" + accountId + "/photo")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegFile().getBytes()))
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();

        String url = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .at("/account/profilePictureUrl").asText();
        String filename = url.substring("/uploads/profile-pictures/".length());

        // user_<id>_<36-char-UUID>.<ext> — the 128-bit random segment makes a
        // REPLACE_EXISTING silent overwrite through random collision impossible.
        assertTrue(filename.matches("user_\\d+_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png|webp)"),
                "Developer uploads must keep the user_<id>_ prefix with a full UUID segment: " + filename);
    }

    // =========================================================
    // §13 FILE-04: replacing an account photo prunes the superseded
    // file immediately after the successful save
    // =========================================================

    @Test
    void developerPhotoReplacementPrunesSupersededFile() throws Exception {
        MvcResult devLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"Dev@12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String devToken = objectMapper.readTree(devLogin.getResponse().getContentAsString()).get("token").asText();

        MvcResult profileResult = mockMvc.perform(get("/profile")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        String accountId = objectMapper.readTree(profileResult.getResponse().getContentAsString())
                .get("id").asText();

        // 1. First upload — file physically exists
        MvcResult first = mockMvc.perform(multipart("/developer/accounts/" + accountId + "/photo")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegFile().getBytes()))
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        String url1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .at("/account/profilePictureUrl").asText();
        Path file1 = fileStorageService.getUploadDirectory()
                .resolve(url1.substring("/uploads/profile-pictures/".length()));
        assertTrue(Files.exists(file1), "First photo must physically exist: " + file1);

        // 2. Replacement upload — superseded file must be pruned immediately
        MvcResult second = mockMvc.perform(multipart("/developer/accounts/" + accountId + "/photo")
                        .file(new MockMultipartFile("photo", "photo2.jpg", "image/jpeg", jpegFile().getBytes()))
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andReturn();
        String url2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .at("/account/profilePictureUrl").asText();

        Path file2 = fileStorageService.getUploadDirectory()
                .resolve(url2.substring("/uploads/profile-pictures/".length()));
        assertTrue(Files.exists(file2), "The replacement photo must physically exist: " + file2);
        assertFalse(Files.exists(file1),
                "The superseded photo must be pruned immediately after the successful save: " + file1);

        // 3. The DB row points at the new URL and the new file is served
        mockMvc.perform(get(url2))
                .andExpect(status().isOk());
        mockMvc.perform(get("/profile").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePictureUrl").value(url2));
    }

    // =========================================================
    // §14 FILE-04: destructive deletion is DISABLED by default —
    // even an old unreferenced file survives until an operator
    // explicitly enables app.photo-cleanup.deletion-enabled
    // =========================================================

    @Test
    void destructiveCleanupIsDisabledByDefault() throws Exception {
        assertFalse(photoCleanupService.isDeletionEnabled(),
                "Dry-run must be the default: deletion must not be enabled by any default property");

        String orphanName = UUID.randomUUID() + ".jpg";
        Path orphan = fileStorageService.getUploadDirectory().resolve(orphanName);
        Files.write(orphan, "orphan-payload".getBytes());
        Files.setLastModifiedTime(orphan,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(48))));

        try {
            PhotoCleanupService.CleanupReport report = photoCleanupService.runCleanup(true);

            assertTrue(report.isDryRun(), "A destructive request against a default configuration must degrade to dry-run");
            assertEquals(0, report.deleted());
            assertTrue(Files.exists(orphan), "No file may be deleted while deletion is disabled: " + orphan);
        } finally {
            Files.deleteIfExists(orphan);
        }
    }

}
