package com.college.attendance;

import com.college.attendance.dto.LeaveRequestDTO;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 7 Plan 07-03: End-to-End Integration Tests.
 *
 * Exercises the complete multi-step lifecycles for:
 * 1. Attendance: Auth -> Verification barrier -> Ticket issuance -> Punch-in -> Duplicate rejection -> Query -> Punch-out.
 * 2. Leave: Auth -> Application -> Pending state -> Cross-dept denial -> Authorized approval -> Immutability check -> Final state.
 *
 * Uses isolated in-memory test database; live MySQL port 3307 is strictly untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class FullLifecycleEndToEndIntegrationTest {

    private static final String DEFAULT_PASSWORD = "Password@123";

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

    @BeforeEach
    void setUp() {
        StubEmailConfiguration.StubEmailService.reset();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        deptA = departmentRepository.save(Department.builder()
                .name("Computer Science " + suffix)
                .code("CS" + suffix)
                .isActive(true)
                .build());

        deptB = departmentRepository.save(Department.builder()
                .name("Mechanical Eng " + suffix)
                .code("ME" + suffix)
                .isActive(true)
                .build());

        facultyA = userRepository.save(User.builder()
                .employeeId("FA" + suffix)
                .firstName("Ada")
                .lastName("Lovelace")
                .email("ada." + suffix + "@college.local")
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(Role.FACULTY)
                .department(deptA)
                .isActive(true)
                .firstLogin(false)
                .build());

        facultyB = userRepository.save(User.builder()
                .employeeId("FB" + suffix)
                .firstName("Grace")
                .lastName("Hopper")
                .email("grace." + suffix + "@college.local")
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(Role.FACULTY)
                .department(deptB)
                .isActive(true)
                .firstLogin(false)
                .build());

        hodA = userRepository.save(User.builder()
                .employeeId("HA" + suffix)
                .firstName("Charles")
                .lastName("Babbage")
                .email("charles." + suffix + "@college.local")
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(Role.HOD)
                .department(deptA)
                .isActive(true)
                .firstLogin(false)
                .build());

        hodB = userRepository.save(User.builder()
                .employeeId("HB" + suffix)
                .firstName("Nikola")
                .lastName("Tesla")
                .email("nikola." + suffix + "@college.local")
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(Role.HOD)
                .department(deptB)
                .isActive(true)
                .firstLogin(false)
                .build());
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

    @Test
    @DisplayName("Complete Attendance Lifecycle: Login -> Verify Guard -> Ticket -> Punch-In -> Query -> Punch-Out -> Duplicate Protection")
    void attendanceLifecycle_completeFlow_loginToVerificationToPunchInAndPunchOut() throws Exception {
        // Step 1: Login as faculty A
        String facultyAToken = login(facultyA.getEmail(), DEFAULT_PASSWORD);
        assertNotNull(facultyAToken, "JWT token must be issued on successful login");

        // Step 2: Attempt direct punch-in without verification ticket -> strictly denied
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + facultyAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Face and location verification are required")));

        // Step 3: Simulate biometric and location verification passing (issuing 256-bit single-use ticket)
        String verificationTicket = faceVerificationService.issueTicket(
                facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.96);
        assertNotNull(verificationTicket, "Verification ticket must be issued");

        // Step 4: Cross-user ticket theft prevention: Faculty B attempts to use Faculty A's ticket
        String stolenTicket = faceVerificationService.issueTicket(
                facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.96);
        String facultyBToken = login(facultyB.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + facultyBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verificationTicket", stolenTicket))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Face and location verification are required")));

        // Step 5: Legitimate Punch-In with valid token and fresh ticket -> succeeds
        String legitimateTicket = faceVerificationService.issueTicket(
                facultyA.getId(), 12.9716, 77.5946, 10.0, 25.0, 0.96);
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + facultyAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verificationTicket", legitimateTicket))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", anyOf(is("PRESENT"), is("LATE"))))
                .andExpect(jsonPath("$.hasPunchedIn").value(true))
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.latitude").value(12.9716))
                .andExpect(jsonPath("$.longitude").value(77.5946));

        // Step 6: Single-use ticket consumed: reusing the same ticket is rejected
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + facultyAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verificationTicket", legitimateTicket))))
                .andExpect(status().isBadRequest());

        // Step 7: Verify attendance state through GET /attendance/today
        mockMvc.perform(get("/attendance/today")
                        .header("Authorization", "Bearer " + facultyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPunchedIn").value(true))
                .andExpect(jsonPath("$.hasPunchedOut").value(false))
                .andExpect(jsonPath("$.status", anyOf(is("PRESENT"), is("LATE"))))
                .andExpect(jsonPath("$.punchInTime", notNullValue()));

        // Step 8: Punch-out -> succeeds and calculates working hours
        mockMvc.perform(post("/attendance/punch-out")
                        .header("Authorization", "Bearer " + facultyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPunchedOut").value(true))
                .andExpect(jsonPath("$.punchOutTime", notNullValue()))
                .andExpect(jsonPath("$.workingHours", notNullValue()));

        // Step 9: Duplicate punch-out attempt -> rejected
        mockMvc.perform(post("/attendance/punch-out")
                        .header("Authorization", "Bearer " + facultyAToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Already punched out for today")));

        // Step 10: Final state confirmation
        mockMvc.perform(get("/attendance/today")
                        .header("Authorization", "Bearer " + facultyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPunchedIn").value(true))
                .andExpect(jsonPath("$.hasPunchedOut").value(true));
    }

    @Test
    @DisplayName("Complete Leave Lifecycle: Apply -> Pending -> Cross-Dept Denied -> Authorized Approval -> Immutability Check")
    void leaveLifecycle_completeFlow_applicationToApprovalToBalanceUpdate() throws Exception {
        // Step 1: Login Faculty A and HODs
        String facultyAToken = login(facultyA.getEmail(), DEFAULT_PASSWORD);
        String hodAToken = login(hodA.getEmail(), DEFAULT_PASSWORD);
        String hodBToken = login(hodB.getEmail(), DEFAULT_PASSWORD);

        // Step 2: Faculty A applies for Casual Leave (3 days)
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = LocalDate.now().plusDays(12);

        LeaveRequestDTO requestDTO = LeaveRequestDTO.builder()
                .leaveType(LeaveType.CASUAL)
                .startDate(start)
                .endDate(end)
                .reason("International Academic Symposium")
                .build();

        MvcResult applyResult = mockMvc.perform(post("/leave/apply")
                        .header("Authorization", "Bearer " + facultyAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.durationDays").value(3))
                .andReturn();

        JsonNode createdNode = objectMapper.readTree(applyResult.getResponse().getContentAsString());
        Long leaveId = createdNode.get("id").asLong();
        assertTrue(leaveId > 0, "Created leave request must have a valid positive ID");

        // Step 3: Faculty A queries /leave/my-leaves -> confirms leave is in PENDING state
        mockMvc.perform(get("/leave/my-leaves")
                        .header("Authorization", "Bearer " + facultyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].id").value(leaveId))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        // Step 4: Cross-department unauthorized review: HOD B (Dept B) attempts to approve Dept A leave -> rejected
        mockMvc.perform(post("/leave/admin/approve/" + leaveId)
                        .header("Authorization", "Bearer " + hodBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("comments", "Unauthorized review"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not authorized to review leave requests for this faculty member")));

        // Step 5: Department HOD A (Dept A) approves the leave -> succeeds
        mockMvc.perform(post("/leave/admin/approve/" + leaveId)
                        .header("Authorization", "Bearer " + hodAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("comments", "Approved for research symposium"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("approved successfully")))
                .andExpect(jsonPath("$.leave.status").value("APPROVED"))
                .andExpect(jsonPath("$.leave.approvedByName", containsString("Babbage")));

        // Step 6: State immutability check: re-approving an already approved leave -> rejected
        mockMvc.perform(post("/leave/admin/approve/" + leaveId)
                        .header("Authorization", "Bearer " + hodAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("comments", "Attempting double approval"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already been APPROVED")));

        // Step 7: State immutability check: rejecting an already approved leave -> rejected
        mockMvc.perform(post("/leave/admin/reject/" + leaveId)
                        .header("Authorization", "Bearer " + hodAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("comments", "Attempting reject after approve"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already been APPROVED")));

        // Step 8: Faculty A queries /leave/my-leaves -> confirms status is APPROVED with reviewer details
        mockMvc.perform(get("/leave/my-leaves")
                        .header("Authorization", "Bearer " + facultyAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(leaveId))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].approvedByName", containsString("Babbage")))
                .andExpect(jsonPath("$[0].reviewerComments").value("Approved for research symposium"));
    }
}
