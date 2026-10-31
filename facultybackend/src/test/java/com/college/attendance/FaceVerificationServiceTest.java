package com.college.attendance;

import com.college.attendance.dto.VerifyAttendanceRequest;
import com.college.attendance.dto.VerifyAttendanceResponse;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.AttendanceService;
import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.support.StubEmailConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class FaceVerificationServiceTest {

    @Autowired
    private FaceVerificationService faceVerificationService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AttendanceLogRepository attendanceLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.college.attendance.service.FileStorageService fileStorageService;

    private User testFaculty;
    private Department testDept;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        testDept = departmentRepository.save(Department.builder()
                .name("CS-" + suffix)
                .code("CS" + suffix.substring(0, 4))
                .build());

        testFaculty = userRepository.save(User.builder()
                .firstName("Test")
                .lastName("Faculty " + suffix)
                .email("faculty." + suffix + "@college.edu")
                .passwordHash(passwordEncoder.encode("Password@123"))
                .employeeId("FAC" + suffix)
                .role(Role.FACULTY)
                .department(testDept)
                .isActive(true)
                .firstLogin(false)
                .build());
    }

    @Test
    void missingRegisteredPhotoReturnsClearErrorMessageAndNoTicket() {
        testFaculty.setProfilePictureUrl(null);
        userRepository.save(testFaculty);

        VerifyAttendanceRequest request = new VerifyAttendanceRequest();
        request.setCapturedPhoto("data:image/jpeg;base64,/9j/4AAQSkZJRg==");
        request.setLatitude(12.9716);
        request.setLongitude(77.5946);
        request.setAccuracy(25.0);

        VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

        assertFalse(response.isVerified());
        assertFalse(response.isFaceMatch());
        assertNull(response.getTicket());
        assertTrue(response.getMessage().contains("no registered profile photo found"),
                "Expected clear missing photo message, got: " + response.getMessage());
    }

    @Test
    void missingFileOnDiskReturnsClearErrorMessageAndNoTicket() {
        testFaculty.setProfilePictureUrl("/uploads/profile-pictures/non_existent_file_" + UUID.randomUUID() + ".jpg");
        userRepository.save(testFaculty);

        VerifyAttendanceRequest request = new VerifyAttendanceRequest();
        request.setCapturedPhoto("data:image/jpeg;base64,/9j/4AAQSkZJRg==");
        request.setLatitude(12.9716);
        request.setLongitude(77.5946);
        request.setAccuracy(25.0);

        VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

        assertFalse(response.isVerified());
        assertFalse(response.isFaceMatch());
        assertNull(response.getTicket());
        assertTrue(response.getMessage().contains("no registered profile photo found"),
                "Expected clear missing file message, got: " + response.getMessage());
    }

    @Test
    void serviceUnavailableReturnsDistinctErrorMessageAndNoTicket() {
        // Point serviceUrl to an invalid closed port to simulate service down
        Object originalUrl = ReflectionTestUtils.getField(faceVerificationService, "faceServiceUrl");
        ReflectionTestUtils.setField(faceVerificationService, "faceServiceUrl", "http://127.0.0.1:59999");

        try {
            // Give faculty a valid-looking dummy file so it attempts calling the service
            testFaculty.setProfilePictureUrl("/uploads/profile-pictures/test_photo.jpg");
            userRepository.save(testFaculty);

            VerifyAttendanceRequest request = new VerifyAttendanceRequest();
            request.setCapturedPhoto("data:image/jpeg;base64,/9j/4AAQSkZJRg==");
            request.setLatitude(12.9716);
            request.setLongitude(77.5946);
            request.setAccuracy(25.0);

            // Create temporary file on disk if needed
            java.nio.file.Path testPath = fileStorageService.getUploadDirectory().resolve("test_photo.jpg");
            java.nio.file.Files.createDirectories(testPath.getParent());
            if (!java.nio.file.Files.exists(testPath)) {
                java.nio.file.Files.write(testPath, new byte[]{1, 2, 3});
            }

            VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

            assertFalse(response.isVerified(), "Must not be verified when service is offline");
            assertFalse(response.isFaceMatch(), "Face match must be false");
            assertNull(response.getTicket(), "Verification ticket must NOT be issued when service is offline");
            assertTrue(response.getMessage().contains("face verification service unavailable"),
                    "Expected distinct service unavailable message, got: " + response.getMessage());
            assertTrue(response.getFaceMessage().contains("unavailable"),
                    "Expected detailed faceMessage with unavailable notice, got: " + response.getFaceMessage());

        } catch (Exception e) {
            fail("Exception thrown during verify: " + e.getMessage());
        } finally {
            ReflectionTestUtils.setField(faceVerificationService, "faceServiceUrl", originalUrl);
        }
    }

    @Test
    void poorLocationAccuracyExceedingMaxAllowedRejectsVerification() {
        testFaculty.setProfilePictureUrl(null);
        userRepository.save(testFaculty);

        VerifyAttendanceRequest request = new VerifyAttendanceRequest();
        request.setLatitude(12.9716);
        request.setLongitude(77.5946);
        // Configured max accuracy is 150m, send 200m
        request.setAccuracy(200.0);

        VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

        assertFalse(response.isLocationValid(), "Location must be invalid when accuracy exceeds max allowed");
        assertFalse(response.isVerified());
        assertNull(response.getTicket());
        assertTrue(response.getLocationMessage().contains("Location accuracy is insufficient"),
                "Expected insufficient accuracy message, got: " + response.getLocationMessage());
    }

    @Test
    void locationOutsideCampusRadiusRejectsVerification() {
        testFaculty.setProfilePictureUrl(null);
        userRepository.save(testFaculty);

        VerifyAttendanceRequest request = new VerifyAttendanceRequest();
        // Somewhere far away (e.g. lat 18.5, lon 79.5 ~ 150km away)
        request.setLatitude(18.500000);
        request.setLongitude(79.500000);
        request.setAccuracy(20.0);

        VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

        assertFalse(response.isLocationValid(), "Location must be invalid when outside radius");
        assertFalse(response.isVerified());
        assertNull(response.getTicket());
        assertTrue(response.getLocationMessage().contains("Outside attendance area"),
                "Expected outside attendance area message, got: " + response.getLocationMessage());
    }

    @Test
    void locationUncertaintyExceedingDistanceToBoundaryRejectsVerification() {
        testFaculty.setProfilePictureUrl(null);
        userRepository.save(testFaculty);

        // Distance near the 3800m boundary:
        // Center is (12.9716, 77.5946). Let's calculate a point approximately 3780m away:
        // 1 degree latitude ~ 111,000 meters. 3780m / 111,000 ~ 0.03405 degrees.
        double targetLat = 12.9716 + (3780.0 / 111000.0);
        double dist = faceVerificationService.calculateDistance(12.9716, 77.5946, targetLat, 77.5946);
        assertTrue(dist <= 3800.0, "Nominal position should be inside 3800m boundary: " + dist);

        double distanceToBoundary = 3800.0 - dist;
        // Set reported accuracy greater than distance to boundary (e.g., boundary is 20m, accuracy is 64m)
        double reportedAccuracy = Math.max(distanceToBoundary + 30.0, 64.0);

        VerifyAttendanceRequest request = new VerifyAttendanceRequest();
        request.setLatitude(targetLat);
        request.setLongitude(77.5946);
        request.setAccuracy(reportedAccuracy);

        VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

        assertFalse(response.isLocationValid(), "Location must be invalid when GPS uncertainty spills outside boundary");
        assertFalse(response.isVerified());
        assertNull(response.getTicket());
        assertTrue(response.getLocationMessage().contains("Location uncertainty"),
                "Expected boundary uncertainty message, got: " + response.getLocationMessage());
    }

    @Test
    void locationInsideCampusWithAcceptableAccuracyPassesLocationCheck() {
        testFaculty.setProfilePictureUrl(null);
        userRepository.save(testFaculty);

        // 18m from college center with 64m accuracy
        // 3800 - 18 = 3782m >> 64m uncertainty, so uncertainty circle is well within campus
        double targetLat = 12.9716 + (18.0 / 111000.0);
        VerifyAttendanceRequest request = new VerifyAttendanceRequest();
        request.setLatitude(targetLat);
        request.setLongitude(77.5946);
        request.setAccuracy(64.0);

        VerifyAttendanceResponse response = faceVerificationService.verify(testFaculty.getId(), request);

        assertTrue(response.isLocationValid(), "Location should be valid when nominal point and uncertainty are within boundary");
        assertTrue(response.getLocationMessage().contains("Location verified"),
                "Expected Location verified message, got: " + response.getLocationMessage());
    }

    @Test
    void directPunchInWithoutTicketFailsAndCreatesNoAttendance() {
        LocalDate today = LocalDate.now();
        assertFalse(attendanceLogRepository.findByUserIdAndAttendanceDate(testFaculty.getId(), today).isPresent());

        // Attempt direct punch-in without ticket
        assertThrows(RuntimeException.class, () -> {
            attendanceService.punchIn(testFaculty.getId(), null);
        });

        // Attempt with forged or expired ticket
        assertThrows(RuntimeException.class, () -> {
            attendanceService.punchIn(testFaculty.getId(), "forged-ticket-12345");
        });

        // Ensure no attendance record was created
        assertFalse(attendanceLogRepository.findByUserIdAndAttendanceDate(testFaculty.getId(), today).isPresent(),
                "No attendance record should be created when ticket is missing or invalid");
    }

    @Test
    void checkServiceHealthReportsStatus() {
        // Test offline status (default test profile points to offline port)
        Map<String, Object> offlineHealth = faceVerificationService.checkServiceHealth();
        assertNotNull(offlineHealth);
        assertTrue(offlineHealth.containsKey("configuredUrl"));
        assertEquals("DOWN", offlineHealth.get("status"));
        assertEquals(false, offlineHealth.get("available"));

        // Test health endpoint inspection when pointed at Python microservice port
        Object originalUrl = ReflectionTestUtils.getField(faceVerificationService, "faceServiceUrl");
        ReflectionTestUtils.setField(faceVerificationService, "faceServiceUrl", "http://localhost:5001");
        try {
            Map<String, Object> serviceHealth = faceVerificationService.checkServiceHealth();
            assertNotNull(serviceHealth);
            assertTrue(serviceHealth.containsKey("status"));
            assertTrue(serviceHealth.containsKey("available"));
        } finally {
            ReflectionTestUtils.setField(faceVerificationService, "faceServiceUrl", originalUrl);
        }
    }
}
