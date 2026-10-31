package com.college.attendance;

import com.college.attendance.dto.LeaveRequestDTO;
import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.LeaveRequest;
import com.college.attendance.entity.User;
import com.college.attendance.enums.AttendanceStatus;
import com.college.attendance.enums.LeaveStatus;
import com.college.attendance.enums.LeaveType;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.LeaveRequestRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.support.StubEmailConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 7 Plan 07-01: Comprehensive Backend Regression and Security Hardening Tests.
 *
 * Covers:
 * 1. Attendance Lifecycle & Ticket Security (punch-out without punch-in, double punch-out,
 *    invalid/stolen/expired ticket, duplicate punch-in, working hours threshold < 4.0h half-day).
 * 2. Leave Lifecycle & Boundary Integrity (invalid date range, overlapping leaves, approval
 *    immutability, self-approval prevention, cancel ownership).
 * 3. BOLA / Access Control Boundaries (faculty admin-endpoint isolation, HOD cross-department
 *    leave approval restriction, HOD cross-department attendance view restriction, non-admin
 *    location config restriction, anonymous access rejection).
 * 4. Error Envelope Consistency (400, 403, 404, 415 return clean structured JSON envelopes
 *    without raw stack traces or internal SQL leakage).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class BackendRegressionAndSecurityHardeningTest {

    private static final String TEST_PASSWORD = "Password@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AttendanceLogRepository attendanceLogRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private FaceVerificationService faceVerificationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department deptA;
    private Department deptB;
    private User facultyA;
    private User facultyB;
    private User hodA;
    private User hodB;

    private String facultyAToken;
    private String facultyBToken;
    private String hodAToken;
    private String hodBToken;

    @BeforeEach
    void setUp() throws Exception {
        StubEmailConfiguration.StubEmailService.reset();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        deptA = departmentRepository.save(Department.builder()
                .name("CompSci " + suffix)
                .code("CS" + suffix)
                .isActive(true)
                .build());

        deptB = departmentRepository.save(Department.builder()
                .name("Electrical " + suffix)
                .code("EE" + suffix)
                .isActive(true)
                .build());

        facultyA = userRepository.save(User.builder()
                .employeeId("FA" + suffix)
                .firstName("Faculty")
                .lastName("DeptA")
                .email("faculty.a." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .role(Role.FACULTY)
                .department(deptA)
                .isActive(true)
                .firstLogin(false)
                .build());

        facultyB = userRepository.save(User.builder()
                .employeeId("FB" + suffix)
                .firstName("Faculty")
                .lastName("DeptB")
                .email("faculty.b." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .role(Role.FACULTY)
                .department(deptB)
                .isActive(true)
                .firstLogin(false)
                .build());

        hodA = userRepository.save(User.builder()
                .employeeId("HA" + suffix)
                .firstName("HOD")
                .lastName("DeptA")
                .email("hod.a." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .role(Role.HOD)
                .department(deptA)
                .isActive(true)
                .firstLogin(false)
                .build());

        hodB = userRepository.save(User.builder()
                .employeeId("HB" + suffix)
                .firstName("HOD")
                .lastName("DeptB")
                .email("hod.b." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .role(Role.HOD)
                .department(deptB)
                .isActive(true)
                .firstLogin(false)
                .build());

        facultyAToken = login(facultyA.getEmail(), TEST_PASSWORD);
        facultyBToken = login(facultyB.getEmail(), TEST_PASSWORD);
        hodAToken = login(hodA.getEmail(), TEST_PASSWORD);
        hodBToken = login(hodB.getEmail(), TEST_PASSWORD);
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    // =========================================================================
    // SECTION 1: ATTENDANCE LIFECYCLE & TICKET SECURITY
    // =========================================================================
    @Nested
    @DisplayName("Attendance Lifecycle & Verification Ticket Security")
    class AttendanceLifecycleTests {

        @Test
        @DisplayName("Punch-out without prior punch-in returns 400 Bad Request")
        void punchOut_withoutPunchIn_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/attendance/punch-out")
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("No punch-in record found for today")));
        }

        @Test
        @DisplayName("Double punch-out returns 400 Bad Request")
        void punchOut_doublePunchOut_returnsBadRequest() throws Exception {
            String ticket = faceVerificationService.issueTicket(
                    facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.95);

            // First punch-in
            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("verificationTicket", ticket))))
                    .andExpect(status().isOk());

            // First punch-out
            mockMvc.perform(post("/attendance/punch-out")
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isOk());

            // Second punch-out attempt
            mockMvc.perform(post("/attendance/punch-out")
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Already punched out for today")));
        }

        @Test
        @DisplayName("Punch-in as faculty without verification ticket returns 400 Bad Request")
        void punchIn_withoutTicket_asFaculty_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Face and location verification are required")));
        }

        @Test
        @DisplayName("Punch-in with ticket belonging to another user returns 400 Bad Request (ticket theft prevention)")
        void punchIn_withStolenTicket_returnsBadRequest() throws Exception {
            // Ticket issued specifically for Faculty A
            String ticketA = faceVerificationService.issueTicket(
                    facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.95);

            // Faculty B attempts to use Faculty A's ticket
            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + facultyBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("verificationTicket", ticketA))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Face and location verification are required")));
        }

        @Test
        @DisplayName("Punch-in with expired verification ticket returns 400 Bad Request")
        void punchIn_withExpiredTicket_returnsBadRequest() throws Exception {
            // Negative TTL creates an already expired ticket
            String expiredTicket = faceVerificationService.issueTicket(
                    facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.95, Duration.ofMinutes(-5));

            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("verificationTicket", expiredTicket))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Face and location verification are required")));
        }

        @Test
        @DisplayName("Punch-in when already punched in today returns 400 Bad Request")
        void punchIn_alreadyPunchedIn_returnsBadRequest() throws Exception {
            String ticket1 = faceVerificationService.issueTicket(
                    facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.95);

            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("verificationTicket", ticket1))))
                    .andExpect(status().isOk());

            String ticket2 = faceVerificationService.issueTicket(
                    facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.95);

            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("verificationTicket", ticket2))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Already punched in for today")));
        }

        @Test
        @DisplayName("Punch-out with working hours < 4.0 marks status as HALF_DAY")
        void punchOut_workingHoursUnderFourHours_marksHalfDay() throws Exception {
            // Seed attendance log punched in 2 hours ago
            attendanceLogRepository.save(AttendanceLog.builder()
                    .user(facultyA)
                    .attendanceDate(LocalDate.now())
                    .punchInTime(LocalDateTime.now().minusHours(2))
                    .status(AttendanceStatus.PRESENT)
                    .build());

            mockMvc.perform(post("/attendance/punch-out")
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("HALF_DAY"))
                    .andExpect(jsonPath("$.workingHours", notNullValue()));

            AttendanceLog saved = attendanceLogRepository.findByUserIdAndAttendanceDate(facultyA.getId(), LocalDate.now()).orElseThrow();
            assertEquals(AttendanceStatus.HALF_DAY, saved.getStatus());
            assertTrue(saved.getWorkingHours().compareTo(BigDecimal.valueOf(4.0)) < 0);
        }

        @Test
        @DisplayName("Punch-out with working hours >= 4.0 preserves PRESENT status")
        void punchOut_workingHoursOverFourHours_marksPresent() throws Exception {
            // Seed attendance log punched in 6 hours ago
            attendanceLogRepository.save(AttendanceLog.builder()
                    .user(facultyB)
                    .attendanceDate(LocalDate.now())
                    .punchInTime(LocalDateTime.now().minusHours(6))
                    .status(AttendanceStatus.PRESENT)
                    .build());

            mockMvc.perform(post("/attendance/punch-out")
                            .header("Authorization", "Bearer " + facultyBToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PRESENT"))
                    .andExpect(jsonPath("$.workingHours", notNullValue()));

            AttendanceLog saved = attendanceLogRepository.findByUserIdAndAttendanceDate(facultyB.getId(), LocalDate.now()).orElseThrow();
            assertEquals(AttendanceStatus.PRESENT, saved.getStatus());
            assertTrue(saved.getWorkingHours().compareTo(BigDecimal.valueOf(4.0)) >= 0);
        }
    }

    // =========================================================================
    // SECTION 2: LEAVE LIFECYCLE & BOUNDARY INTEGRITY
    // =========================================================================
    @Nested
    @DisplayName("Leave Lifecycle & Boundary Integrity")
    class LeaveLifecycleTests {

        @Test
        @DisplayName("Leave application with startDate after endDate returns 400 Bad Request")
        void applyLeave_invalidDateRange_returnsBadRequest() throws Exception {
            LocalDate start = LocalDate.now().plusDays(10);
            LocalDate end = LocalDate.now().plusDays(5); // Inverted

            LeaveRequestDTO request = LeaveRequestDTO.builder()
                    .leaveType(LeaveType.CASUAL)
                    .startDate(start)
                    .endDate(end)
                    .reason("Invalid dates test")
                    .build();

            mockMvc.perform(post("/leave/apply")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Start date cannot be after end date")));
        }

        @Test
        @DisplayName("Leave application overlapping existing pending/approved leave returns 400 Bad Request")
        void applyLeave_overlappingDates_returnsBadRequest() throws Exception {
            LocalDate start1 = LocalDate.now().plusDays(10);
            LocalDate end1 = LocalDate.now().plusDays(14);

            LeaveRequestDTO request1 = LeaveRequestDTO.builder()
                    .leaveType(LeaveType.CASUAL)
                    .startDate(start1)
                    .endDate(end1)
                    .reason("Initial valid leave")
                    .build();

            mockMvc.perform(post("/leave/apply")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request1)))
                    .andExpect(status().isOk());

            // Second leave overlapping dates: start on day 12
            LeaveRequestDTO request2 = LeaveRequestDTO.builder()
                    .leaveType(LeaveType.SICK)
                    .startDate(LocalDate.now().plusDays(12))
                    .endDate(LocalDate.now().plusDays(16))
                    .reason("Overlapping leave request")
                    .build();

            mockMvc.perform(post("/leave/apply")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request2)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("already have a leave request for the selected dates")));
        }

        @Test
        @DisplayName("Approving an already approved leave request returns 400 Bad Request")
        void approveLeave_alreadyApproved_returnsBadRequest() throws Exception {
            LeaveRequest leave = leaveRequestRepository.save(LeaveRequest.builder()
                    .user(facultyA)
                    .leaveType(LeaveType.CASUAL)
                    .startDate(LocalDate.now().plusDays(20))
                    .endDate(LocalDate.now().plusDays(22))
                    .reason("Vacation")
                    .status(LeaveStatus.PENDING)
                    .approvalToken(UUID.randomUUID().toString())
                    .approvalTokenExpiry(LocalDateTime.now().plusHours(72))
                    .build());

            // First approval by HOD of Dept A -> OK
            mockMvc.perform(post("/leave/admin/approve/" + leave.getId())
                            .header("Authorization", "Bearer " + hodAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("comments", "Approved 1"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", containsString("approved successfully")));

            // Second approval attempt -> Bad Request
            mockMvc.perform(post("/leave/admin/approve/" + leave.getId())
                            .header("Authorization", "Bearer " + hodAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("comments", "Approved 2"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("already been APPROVED")));
        }

        @Test
        @DisplayName("Rejecting an already approved leave request returns 400 Bad Request")
        void rejectLeave_alreadyApproved_returnsBadRequest() throws Exception {
            LeaveRequest leave = leaveRequestRepository.save(LeaveRequest.builder()
                    .user(facultyA)
                    .leaveType(LeaveType.CASUAL)
                    .startDate(LocalDate.now().plusDays(25))
                    .endDate(LocalDate.now().plusDays(27))
                    .reason("Trip")
                    .status(LeaveStatus.APPROVED)
                    .approvedBy(hodA)
                    .reviewedAt(LocalDateTime.now())
                    .approvalToken(UUID.randomUUID().toString())
                    .approvalTokenExpiry(LocalDateTime.now().plusHours(72))
                    .build());

            mockMvc.perform(post("/leave/admin/reject/" + leave.getId())
                            .header("Authorization", "Bearer " + hodAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("comments", "Reject after approve"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("already been APPROVED")));
        }

        @Test
        @DisplayName("Approver cannot approve their own leave request (self-approval prevention)")
        void approveLeave_selfApproval_returnsBadRequest() throws Exception {
            LeaveRequest hodLeave = leaveRequestRepository.save(LeaveRequest.builder()
                    .user(hodA)
                    .leaveType(LeaveType.CASUAL)
                    .startDate(LocalDate.now().plusDays(30))
                    .endDate(LocalDate.now().plusDays(32))
                    .reason("HOD personal leave")
                    .status(LeaveStatus.PENDING)
                    .approvalToken(UUID.randomUUID().toString())
                    .approvalTokenExpiry(LocalDateTime.now().plusHours(72))
                    .build());

            mockMvc.perform(post("/leave/admin/approve/" + hodLeave.getId())
                            .header("Authorization", "Bearer " + hodAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("comments", "Self-approving"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("cannot approve or reject your own leave request")));
        }

        @Test
        @DisplayName("Cancelling another user's leave request returns 400 Bad Request (BOLA check)")
        void cancelLeave_notOwner_returnsBadRequest() throws Exception {
            LeaveRequest leaveA = leaveRequestRepository.save(LeaveRequest.builder()
                    .user(facultyA)
                    .leaveType(LeaveType.CASUAL)
                    .startDate(LocalDate.now().plusDays(35))
                    .endDate(LocalDate.now().plusDays(36))
                    .reason("Faculty A leave")
                    .status(LeaveStatus.PENDING)
                    .approvalToken(UUID.randomUUID().toString())
                    .approvalTokenExpiry(LocalDateTime.now().plusHours(72))
                    .build());

            // Faculty B attempts to cancel Faculty A's leave
            mockMvc.perform(post("/leave/cancel/" + leaveA.getId())
                            .header("Authorization", "Bearer " + facultyBToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("You can only cancel your own leave requests")));
        }

        @Test
        @DisplayName("Cancelling an already approved leave request returns 400 Bad Request")
        void cancelLeave_alreadyApproved_returnsBadRequest() throws Exception {
            LeaveRequest leave = leaveRequestRepository.save(LeaveRequest.builder()
                    .user(facultyA)
                    .leaveType(LeaveType.CASUAL)
                    .startDate(LocalDate.now().plusDays(40))
                    .endDate(LocalDate.now().plusDays(42))
                    .reason("Vacation")
                    .status(LeaveStatus.APPROVED)
                    .approvedBy(hodA)
                    .reviewedAt(LocalDateTime.now())
                    .approvalToken(UUID.randomUUID().toString())
                    .approvalTokenExpiry(LocalDateTime.now().plusHours(72))
                    .build());

            mockMvc.perform(post("/leave/cancel/" + leave.getId())
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Can only cancel pending leave requests")));
        }
    }

    // =========================================================================
    // SECTION 3: BOLA & ROLE-BASED ACCESS CONTROL
    // =========================================================================
    @Nested
    @DisplayName("BOLA & Access Control Boundaries")
    class AccessControlBoundaryTests {

        @Test
        @DisplayName("Faculty cannot access /leave/admin/pending (403 Forbidden)")
        void faculty_cannotAccessAdminPendingLeaves_returnsForbidden() throws Exception {
            mockMvc.perform(get("/leave/admin/pending")
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Faculty cannot access /leave/admin/department/{id} (403 Forbidden)")
        void faculty_cannotAccessAdminDepartmentLeaves_returnsForbidden() throws Exception {
            mockMvc.perform(get("/leave/admin/department/" + deptA.getId())
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Faculty cannot access /leave/admin/approve/{id} (403 Forbidden)")
        void faculty_cannotApproveLeave_returnsForbidden() throws Exception {
            mockMvc.perform(post("/leave/admin/approve/12345")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comments\":\"hack\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("HOD cannot approve leaves of faculty in another department (400 Bad Request)")
        void hod_cannotApproveOtherDepartmentLeave_returnsBadRequest() throws Exception {
            // Leave request created by Faculty B in Dept B
            LeaveRequest leaveB = leaveRequestRepository.save(LeaveRequest.builder()
                    .user(facultyB)
                    .leaveType(LeaveType.CASUAL)
                    .startDate(LocalDate.now().plusDays(50))
                    .endDate(LocalDate.now().plusDays(52))
                    .reason("Dept B leave")
                    .status(LeaveStatus.PENDING)
                    .approvalToken(UUID.randomUUID().toString())
                    .approvalTokenExpiry(LocalDateTime.now().plusHours(72))
                    .build());

            // HOD A (from Dept A) attempts to approve Faculty B's leave
            mockMvc.perform(post("/leave/admin/approve/" + leaveB.getId())
                            .header("Authorization", "Bearer " + hodAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("comments", "Cross-dept approval"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("not authorized to review leave requests for this faculty member")));
        }

        @Test
        @DisplayName("HOD cannot view attendance of another department (403 Forbidden)")
        void hod_cannotViewOtherDepartmentAttendance_returnsForbidden() throws Exception {
            // HOD A attempts to view Dept B's attendance
            mockMvc.perform(get("/attendance/admin/department/" + deptB.getId())
                            .header("Authorization", "Bearer " + hodAToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("HOD can view attendance of their own department (200 OK)")
        void hod_canViewOwnDepartmentAttendance_returnsOk() throws Exception {
            // HOD A views Dept A's attendance
            mockMvc.perform(get("/attendance/admin/department/" + deptA.getId())
                            .header("Authorization", "Bearer " + hodAToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Faculty or HOD cannot update college location configuration (403 Forbidden)")
        void facultyOrHod_cannotUpdateLocationConfig_returnsForbidden() throws Exception {
            String configJson = """
                    {
                        "latitude": 12.9716,
                        "longitude": 77.5946,
                        "radiusMeters": 500,
                        "maxAccuracyMeters": 100
                    }
                    """;

            mockMvc.perform(put("/attendance/admin/location-config")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(configJson))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/attendance/admin/location-config")
                            .header("Authorization", "Bearer " + hodAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(configJson))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated request to protected endpoint returns 403 Forbidden")
        void unauthenticated_protectedEndpoint_returnsForbidden() throws Exception {
            mockMvc.perform(get("/attendance/today"))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // SECTION 4: ERROR ENVELOPE CONSISTENCY & INFORMATION DISCLOSURE
    // =========================================================================
    @Nested
    @DisplayName("Error Envelopes & Security Exception Handling")
    class ErrorEnvelopeTests {

        @Test
        @DisplayName("Unknown endpoint returns 404 with structured JSON envelope")
        void unknownEndpoint_returnsNotFoundWithStandardEnvelope() throws Exception {
            mockMvc.perform(get("/api/nonexistent-route-xyz")
                            .header("Authorization", "Bearer " + facultyAToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Resource not found"));
        }

        @Test
        @DisplayName("Unsupported media type returns 415 with structured JSON envelope")
        void unsupportedMediaType_returnsUnsupportedMediaTypeWithStandardEnvelope() throws Exception {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("plain text payload"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.status").value(415))
                    .andExpect(jsonPath("$.message").value("Unsupported content type"));
        }

        @Test
        @DisplayName("Malformed JSON payload returns 400 without leaking stack traces or SQL details")
        void malformedJson_returnsBadRequestWithoutStackTrace() throws Exception {
            MvcResult result = mockMvc.perform(post("/leave/apply")
                            .header("Authorization", "Bearer " + facultyAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"leaveType\": \"CASUAL\", \"startDate\": bad-json"))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            assertFalse(responseBody.contains("at com.college.attendance"), "Response must not leak Java package traces");
            assertFalse(responseBody.contains("NullPointerException"), "Response must not leak internal exceptions");
            assertFalse(responseBody.contains("org.hibernate"), "Response must not leak Hibernate internals");
        }
    }
}
