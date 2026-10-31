package com.college.attendance;

import com.college.attendance.entity.Department;
import com.college.attendance.entity.LeaveRequest;
import com.college.attendance.entity.User;
import com.college.attendance.enums.LeaveStatus;
import com.college.attendance.enums.LeaveType;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.LeaveRequestRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.security.JwtUtil;
import com.college.attendance.support.StubEmailConfiguration;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class SecurityAuditVerificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department cseDept;
    private Department meDept;
    private User hodCse;
    private String hodCseToken;

    @BeforeEach
    void setUp() {
        cseDept = departmentRepository.findByCode("CSE").orElseGet(() ->
                departmentRepository.save(Department.builder().name("Computer Science").code("CSE").build()));
        meDept = departmentRepository.findByCode("ME").orElseGet(() ->
                departmentRepository.save(Department.builder().name("Mechanical").code("ME").build()));

        String hodEmail = "hod.cse.test." + UUID.randomUUID() + "@college.edu";
        hodCse = userRepository.save(User.builder()
                .employeeId("HOD" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("HOD")
                .lastName("CSE")
                .email(hodEmail)
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.HOD)
                .department(cseDept)
                .isActive(true)
                .build());

        hodCseToken = jwtUtil.generateToken(hodCse);
    }

    @Test
    void hodCannotViewOtherDepartmentAttendance() throws Exception {
        // HOD CSE querying ME department attendance -> must be 403 Forbidden
        mockMvc.perform(get("/attendance/admin/department/" + meDept.getId())
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isForbidden());

        // HOD CSE querying CSE department attendance -> 200 OK
        mockMvc.perform(get("/attendance/admin/department/" + cseDept.getId())
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isOk());
    }

    @Test
    void hodCannotViewOtherDepartmentLeaves() throws Exception {
        // HOD CSE querying ME department leaves -> must be 403 Forbidden
        mockMvc.perform(get("/leave/admin/department/" + meDept.getId())
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isForbidden());

        // HOD CSE querying CSE department leaves -> 200 OK
        mockMvc.perform(get("/leave/admin/department/" + cseDept.getId())
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isOk());
    }

    @Test
    void hodPendingLeavesAreScopedToOwnDepartment() throws Exception {
        // Querying with ME departmentId -> 403 Forbidden
        mockMvc.perform(get("/leave/admin/pending?departmentId=" + meDept.getId())
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isForbidden());

        // Querying without departmentId -> automatically scoped, returns 200
        mockMvc.perform(get("/leave/admin/pending")
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isOk());
    }

    @Test
    void emailEndpointsCannotMutateLeaveStatus() throws Exception {
        String token = UUID.randomUUID().toString();
        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(hodCse)
                .leaveType(LeaveType.CASUAL)
                .startDate(LocalDate.now().plusDays(2))
                .endDate(LocalDate.now().plusDays(3))
                .reason("Personal")
                .approvalToken(token)
                .approvalTokenExpiry(LocalDateTime.now().plusDays(2))
                .status(LeaveStatus.PENDING)
                .build());

        // Anonymous GET to /leave/approve/{token} must be rejected (403 Forbidden)
        mockMvc.perform(get("/leave/approve/" + token))
                .andExpect(status().isForbidden());

        // Anonymous GET with confirm=true must also be rejected (403 Forbidden)
        mockMvc.perform(get("/leave/approve/" + token + "?confirm=true"))
                .andExpect(status().isForbidden());

        // Leave request status must remain PENDING
        LeaveRequest afterAttempt = leaveRequestRepository.findById(lr.getId()).orElseThrow();
        assertThat(afterAttempt.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    void legacyEmailEndpointWhenAuthenticatedCannotMutateLeaveStatus() throws Exception {
        String token = UUID.randomUUID().toString();
        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(hodCse)
                .leaveType(LeaveType.CASUAL)
                .startDate(LocalDate.now().plusDays(2))
                .endDate(LocalDate.now().plusDays(3))
                .reason("Personal")
                .approvalToken(token)
                .approvalTokenExpiry(LocalDateTime.now().plusDays(2))
                .status(LeaveStatus.PENDING)
                .build());

        // Even with valid JWT, legacy email approve/reject URLs return 404 (endpoint removed)
        mockMvc.perform(get("/leave/approve/" + token)
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/leave/reject/" + token)
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isNotFound());

        LeaveRequest afterAttempt = leaveRequestRepository.findById(lr.getId()).orElseThrow();
        assertThat(afterAttempt.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    void authorizedApproverCanApproveViaWebsite() throws Exception {
        User facultyCse = userRepository.save(User.builder()
                .employeeId("FAC" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Faculty")
                .lastName("CSE")
                .email("faculty.cse." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.FACULTY)
                .department(cseDept)
                .isActive(true)
                .build());

        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(facultyCse)
                .leaveType(LeaveType.CASUAL)
                .startDate(LocalDate.now().plusDays(2))
                .endDate(LocalDate.now().plusDays(3))
                .reason("Conference attendance")
                .status(LeaveStatus.PENDING)
                .build());

        // HOD CSE approves via website POST endpoint
        mockMvc.perform(post("/leave/admin/approve/" + lr.getId())
                        .header("Authorization", "Bearer " + hodCseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"Approved, coverage arranged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Leave request approved successfully"))
                .andExpect(jsonPath("$.leave.status").value("APPROVED"));

        LeaveRequest afterApproval = leaveRequestRepository.findById(lr.getId()).orElseThrow();
        assertThat(afterApproval.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(afterApproval.getReviewerComments()).isEqualTo("Approved, coverage arranged");
        assertThat(afterApproval.getApprovedBy().getId()).isEqualTo(hodCse.getId());
    }

    @Test
    void authorizedApproverCanRejectViaWebsite() throws Exception {
        User facultyCse = userRepository.save(User.builder()
                .employeeId("FAC" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Faculty")
                .lastName("CSE")
                .email("faculty.cse.rej." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.FACULTY)
                .department(cseDept)
                .isActive(true)
                .build());

        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(facultyCse)
                .leaveType(LeaveType.SICK)
                .startDate(LocalDate.now().plusDays(1))
                .endDate(LocalDate.now().plusDays(2))
                .reason("Need rest")
                .status(LeaveStatus.PENDING)
                .build());

        // HOD CSE rejects via website POST endpoint
        mockMvc.perform(post("/leave/admin/reject/" + lr.getId())
                        .header("Authorization", "Bearer " + hodCseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"Exam duty scheduled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Leave request rejected"))
                .andExpect(jsonPath("$.leave.status").value("REJECTED"));

        LeaveRequest afterRejection = leaveRequestRepository.findById(lr.getId()).orElseThrow();
        assertThat(afterRejection.getStatus()).isEqualTo(LeaveStatus.REJECTED);
        assertThat(afterRejection.getReviewerComments()).isEqualTo("Exam duty scheduled");
    }

    @Test
    void unauthorizedUserCannotApproveViaWebsite() throws Exception {
        User facultyCse = userRepository.save(User.builder()
                .employeeId("FAC" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Faculty")
                .lastName("CSE")
                .email("faculty.cse.unauth." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.FACULTY)
                .department(cseDept)
                .isActive(true)
                .build());

        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(facultyCse)
                .leaveType(LeaveType.CASUAL)
                .startDate(LocalDate.now().plusDays(2))
                .endDate(LocalDate.now().plusDays(3))
                .reason("Conference")
                .status(LeaveStatus.PENDING)
                .build());

        // HOD of ME department cannot approve CSE faculty leave
        User hodMe = userRepository.save(User.builder()
                .employeeId("HOD" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("HOD")
                .lastName("ME")
                .email("hod.me.test." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.HOD)
                .department(meDept)
                .isActive(true)
                .build());
        String hodMeToken = jwtUtil.generateToken(hodMe);

        mockMvc.perform(post("/leave/admin/approve/" + lr.getId())
                        .header("Authorization", "Bearer " + hodMeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"Cross-department attempt\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not authorized to review leave requests")));

        LeaveRequest afterAttempt = leaveRequestRepository.findById(lr.getId()).orElseThrow();
        assertThat(afterAttempt.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    void applicantCannotApproveOwnLeaveViaWebsite() throws Exception {
        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(hodCse)
                .leaveType(LeaveType.CASUAL)
                .startDate(LocalDate.now().plusDays(2))
                .endDate(LocalDate.now().plusDays(3))
                .reason("Personal")
                .status(LeaveStatus.PENDING)
                .build());

        // HOD CSE attempts to approve own leave -> must be rejected
        mockMvc.perform(post("/leave/admin/approve/" + lr.getId())
                        .header("Authorization", "Bearer " + hodCseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"Self approval\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot approve or reject your own leave request"));

        LeaveRequest afterAttempt = leaveRequestRepository.findById(lr.getId()).orElseThrow();
        assertThat(afterAttempt.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    void cannotApproveAlreadyProcessedLeave() throws Exception {
        User facultyCse = userRepository.save(User.builder()
                .employeeId("FAC" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Faculty")
                .lastName("CSE")
                .email("faculty.cse.done." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.FACULTY)
                .department(cseDept)
                .isActive(true)
                .build());

        LeaveRequest lr = leaveRequestRepository.save(LeaveRequest.builder()
                .user(facultyCse)
                .leaveType(LeaveType.CASUAL)
                .startDate(LocalDate.now().plusDays(2))
                .endDate(LocalDate.now().plusDays(3))
                .reason("Personal")
                .status(LeaveStatus.APPROVED)
                .build());

        mockMvc.perform(post("/leave/admin/approve/" + lr.getId())
                        .header("Authorization", "Bearer " + hodCseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"Double approval\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already been APPROVED")));
    }

    @Test
    void cannotApproveNonExistentLeave() throws Exception {
        mockMvc.perform(post("/leave/admin/approve/99999999")
                        .header("Authorization", "Bearer " + hodCseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"Nonexistent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Leave request not found"));
    }

    @Test
    void hodReceivesOnlyOwnDepartmentInDashboardStats() throws Exception {
        mockMvc.perform(get("/attendance/admin/stats")
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentBreakdown", hasSize(1)))
                .andExpect(jsonPath("$.departmentBreakdown[0].departmentCode", is("CSE")));
    }

    @Test
    void developerReceivesAllDepartmentsInDashboardStats() throws Exception {
        userRepository.save(User.builder()
                .employeeId("FAC" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Faculty")
                .lastName("ME")
                .email("fac.me." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.FACULTY)
                .department(meDept)
                .isActive(true)
                .build());

        User dev = userRepository.save(User.builder()
                .employeeId("DEV" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Developer")
                .lastName("Admin")
                .email("dev.test." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.DEVELOPER)
                .isActive(true)
                .build());
        String devToken = jwtUtil.generateToken(dev);

        mockMvc.perform(get("/attendance/admin/stats")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentBreakdown.length()", greaterThanOrEqualTo(2)));
    }

    @Test
    void tpoBoundToDepartmentCannotViewOtherDepartmentAttendance() throws Exception {
        User tpoCse = userRepository.save(User.builder()
                .employeeId("TPO" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("TPO")
                .lastName("CSE")
                .email("tpo.cse." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.TPO)
                .department(cseDept)
                .isActive(true)
                .build());
        String tpoToken = jwtUtil.generateToken(tpoCse);

        // Mismatched department -> 403 Forbidden
        mockMvc.perform(get("/attendance/admin/department/" + meDept.getId())
                        .header("Authorization", "Bearer " + tpoToken))
                .andExpect(status().isForbidden());

        // Own department -> 200 OK
        mockMvc.perform(get("/attendance/admin/department/" + cseDept.getId())
                        .header("Authorization", "Bearer " + tpoToken))
                .andExpect(status().isOk());
    }

    @Test
    void tpoBoundToDepartmentCannotViewOtherDepartmentLeaves() throws Exception {
        User tpoCse = userRepository.save(User.builder()
                .employeeId("TPO" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("TPO")
                .lastName("CSE")
                .email("tpo.cse.leaves." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.TPO)
                .department(cseDept)
                .isActive(true)
                .build());
        String tpoToken = jwtUtil.generateToken(tpoCse);

        // Mismatched department -> 403 Forbidden
        mockMvc.perform(get("/leave/admin/department/" + meDept.getId())
                        .header("Authorization", "Bearer " + tpoToken))
                .andExpect(status().isForbidden());

        // Own department -> 200 OK
        mockMvc.perform(get("/leave/admin/department/" + cseDept.getId())
                        .header("Authorization", "Bearer " + tpoToken))
                .andExpect(status().isOk());
    }

    @Test
    void hodCannotAccessTeamAllEndpoint() throws Exception {
        mockMvc.perform(get("/team/all")
                        .header("Authorization", "Bearer " + hodCseToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void facultyCannotAccessTeamAllEndpoint() throws Exception {
        User faculty = userRepository.save(User.builder()
                .employeeId("FAC" + UUID.randomUUID().toString().substring(0, 6))
                .firstName("Faculty")
                .lastName("CSE")
                .email("fac.cse." + UUID.randomUUID() + "@college.edu")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.FACULTY)
                .department(cseDept)
                .isActive(true)
                .build());
        String facToken = jwtUtil.generateToken(faculty);

        mockMvc.perform(get("/team/all")
                        .header("Authorization", "Bearer " + facToken))
                .andExpect(status().isForbidden());
    }
}
