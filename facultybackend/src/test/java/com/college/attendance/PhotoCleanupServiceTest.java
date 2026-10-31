package com.college.attendance;

import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.FileStorageService;
import com.college.attendance.service.PhotoCleanupService;
import com.college.attendance.support.StubEmailConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FILE-04 orphaned profile-photo lifecycle tests.
 *
 * Deletion is explicitly enabled for this class (separate test context) so
 * destructive sweeps can be exercised; the default-disabled behavior is
 * verified in ProfilePictureUploadTest against the default context.
 */
@SpringBootTest(properties = {
        "app.photo-cleanup.deletion-enabled=true",
        "app.photo-cleanup.retention-hours=24"
})
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cleanupTestdb;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE")
class PhotoCleanupServiceTest {

    @Autowired
    private PhotoCleanupService photoCleanupService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Path dir;

    @BeforeEach
    void setUp() {
        dir = fileStorageService.getUploadDirectory();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ fixtures

    private String suffix() {
        return UUID.randomUUID().toString();
    }

    private Path writeFile(String name, boolean old) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, ("payload:" + name).getBytes());
        if (old) {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(34))));
        }
        return file;
    }

    private User userReferringTo(String filename) {
        String sfx = suffix().substring(0, 8);
        Department department = departmentRepository.findByCode("CLNP" + sfx)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("Cleanup Dept " + sfx)
                        .code("CLNP" + sfx)
                        .isActive(true)
                        .build()));
        User user = userRepository.save(User.builder()
                .employeeId("CLNU" + sfx)
                .firstName("Cleanup")
                .lastName("Test")
                .email("cleanup." + sfx + "@test.local")
                .passwordHash(passwordEncoder.encode("Cleanup@123"))
                .role(Role.FACULTY)
                .department(department)
                .isActive(true)
                .firstLogin(false)
                .profilePictureUrl("/uploads/profile-pictures/" + filename)
                .build());
        assertTrue(userRepository.findById(user.getId()).isPresent());
        return user;
    }

    private String uuidName(String ext) {
        return UUID.randomUUID() + ext;
    }

    private boolean inReportOrphans(PhotoCleanupService.CleanupReport report, String name) {
        return report.orphanFilenames().contains(name);
    }

    // ------------------------------------------------------------ logic checks

    @Test
    void serviceDefaultsExposeConfiguration() {
        assertTrue(photoCleanupService.isDeletionEnabled(), "class-level property enables deletion");
        assertEquals(24, photoCleanupService.getRetentionHours());
    }

    @Test
    void referencedActiveUserFileIsProtected() throws Exception {
        String name = uuidName(".jpg");
        Path file = writeFile(name, true);
        userReferringTo(name);

        photoCleanupService.runCleanup(true);

        assertTrue(Files.exists(file), "A file referenced by an active user row must never be deleted: " + file);
        assertFalse(inReportOrphans(photoCleanupService.report(), name));
    }

    @Test
    void referencedDeactivatedUserFileIsProtected() throws Exception {
        String name = uuidName(".jpg");
        Path file = writeFile(name, true);
        User user = userReferringTo(name);
        user.setIsActive(false);
        userRepository.save(user);

        photoCleanupService.runCleanup(true);

        assertTrue(Files.exists(file), "A file referenced even by a DEACTIVATED user row must never be deleted: " + file);
    }

    @Test
    void legacyNamedUserFileWithReferenceIsProtected() throws Exception {
        // Stone-era legacy naming (user_<id>_<8hex>.jpg) referenced by a user row
        String name = "user_99_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        Path file = writeFile(name, true);
        userReferringTo(name);

        photoCleanupService.runCleanup(true);

        assertTrue(Files.exists(file), "Referenced legacy-named file must never be deleted: " + file);
    }

    @Test
    void orphanDeletedOnlyAfterGracePeriod() throws Exception {
        String name = uuidName(".jpg");
        Path file = writeFile(name, true); // aged 34h > 24h retention, unreferenced

        PhotoCleanupService.CleanupReport report = photoCleanupService.runCleanup(true);

        assertFalse(Files.exists(file), "Unreferenced managed file beyond the retention window must be reclaimed: " + file);
        assertEquals(1, report.deleted());
        assertEquals(1, report.orphanCandidates());
    }

    @Test
    void gracePeriodProtectsFreshUnreferencedFile() throws Exception {
        String name = uuidName(".png");
        Path file = writeFile(name, false); // fresh: within the 24h window

        PhotoCleanupService.CleanupReport report = photoCleanupService.runCleanup(true);

        assertTrue(Files.exists(file), "Unreferenced file inside the grace period must be kept (crash window safety): " + file);
        assertEquals(0, report.deleted());
        // The upload directory is shared with other test suites' leftovers; our
        // fresh file must be among the grace-protected ones.
        assertTrue(report.graceProtected() >= 1,
                "Fresh unreferenced files must be grace-protected, got " + report.graceProtected());
    }

    @Test
    void unexpectedFilenamesAreNeverDeleted() throws Exception {
        Path notes = writeFile("operator-notes.txt", true);
        Path oddImage = writeFile("cam_snap_20260101_old.jpg", true);
        try {
            PhotoCleanupService.CleanupReport report = photoCleanupService.runCleanup(true);
            assertTrue(Files.exists(notes), "Non-image artifacts must never be auto-deleted: " + notes);
            assertTrue(Files.exists(oddImage), "Unrecognized image-like names must never be auto-deleted: " + oddImage);
            assertTrue(report.unrecognizedKept() >= 2,
                    "Unexpected names must be counted as unrecognizedKept, got " + report.unrecognizedKept());
        } finally {
            Files.deleteIfExists(notes);
            Files.deleteIfExists(oddImage);
        }
    }

    @Test
    void reportIsPureDryRun() throws Exception {
        String name = uuidName(".jpg");
        Path file = writeFile(name, true); // orphan candidate

        PhotoCleanupService.CleanupReport report = photoCleanupService.report();

        assertTrue(report.isDryRun());
        assertTrue(inReportOrphans(report, name), "The orphan must be reported: " + name);
        assertTrue(Files.exists(file), "A dry-run report must never delete anything: " + file);
    }

    @Test
    void sharedUrlProtectedFromPrune() throws Exception {
        String name = uuidName(".jpg");
        Path file = writeFile(name, false);
        User first = userReferringTo(name);
        User second = userReferringTo(name); // two accounts referencing one file

        boolean pruned = photoCleanupService.pruneSupersededPhoto("/uploads/profile-pictures/" + name, first.getId());

        assertFalse(pruned, "A filename still referenced by any account must not be pruned");
        assertTrue(Files.exists(file), "Shared reference must protect the file: " + file);
        assertTrue(userRepository.findById(second.getId()).isPresent());
    }

    @Test
    void pruneRejectsTraversalAndTouchesNothingOutsideDirectory() throws Exception {
        Path siblingDir = dir.getParent().resolve("outside-profile-pictures");
        Files.createDirectories(siblingDir);
        Path outside = siblingDir.resolve("outside.jpg");
        Files.write(outside, "outside".getBytes());

        try {
            // Traversal prefixes are neutralized by basename-stripping (mirrors
            // resolvePath()): the resolved target can only ever sit inside the
            // approved directory, so these calls must NOT delete the outside file.
            assertFalse(photoCleanupService.pruneSupersededPhoto("../outside-profile-pictures/outside.jpg", 1L));
            assertFalse(fileStorageService.deleteStoredProfilePicture("..\\outside-profile-pictures\\outside.jpg"));
            assertFalse(fileStorageService.deleteStoredProfilePicture("../outside-profile-pictures/outside.jpg"));

            assertTrue(Files.exists(outside),
                    "Deletion must never reach outside the approved upload directory: " + outside);

            // A surviving '..' inside the bare filename is rejected outright.
            assertThrows(SecurityException.class, () ->
                    fileStorageService.deleteStoredProfilePicture(".."));
            assertThrows(SecurityException.class, () ->
                    fileStorageService.deleteStoredProfilePicture("a..i.jpg"));
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(siblingDir);
        }
    }

    @Test
    void pruneRefusesUnmanagedNames() throws Exception {
        Path file = writeFile("photo-keep-me.jpg", true);

        boolean pruned = photoCleanupService.pruneSupersededPhoto("/uploads/profile-pictures/photo-keep-me.jpg", 1L);

        assertFalse(pruned, "Non-system-generated names must never be deleted automatically");
        assertTrue(Files.exists(file), "An unexpected name must survive pruneSupersededPhoto requests: " + file);
        Files.deleteIfExists(file);
    }

    @Test
    void pruneIntoNullAndBlankUrlsIsNoOp() {
        assertFalse(photoCleanupService.pruneSupersededPhoto(null, 1L));
        assertFalse(photoCleanupService.pruneSupersededPhoto("   ", 1L));
    }
}
