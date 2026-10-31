package com.college.attendance;

import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AuditLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.support.StubEmailConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access control for the DEVELOPER / SUPER ADMIN module:
 * only DEVELOPER accounts may reach /developer/**, and privileged fields
 * cannot be escalated through manipulated requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class DeveloperSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Email sending is stubbed so tests never need real SMTP. */
    @Autowired
    private StubEmailConfiguration.StubEmailService emailService;

    private String facultyEmail;
    private String facultyPassword = "Faculty@123";

    @BeforeEach
    void setUp() {
        StubEmailConfiguration.StubEmailService.reset();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Department department = departmentRepository.findByCode("TSEC" + suffix)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("Security Test Dept " + suffix)
                        .code("TSEC" + suffix)
                        .isActive(true)
                        .build()));

        facultyEmail = "faculty." + suffix + "@test.local";
        if (userRepository.findByEmail(facultyEmail).isEmpty()) {
            userRepository.save(User.builder()
                    .employeeId("TSF" + suffix)
                    .firstName("Test")
                    .lastName("Faculty")
                    .email(facultyEmail)
                    .passwordHash(passwordEncoder.encode(facultyPassword))
                    .role(Role.FACULTY)
                    .department(department)
                    .isActive(true)
                    .firstLogin(false)
                    .build());
        }
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
    void anonymousRequestToDeveloperApiIsRejected() throws Exception {
        mockMvc.perform(get("/developer/accounts"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/developer/stats"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/developer/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void developerCanUseDeveloperApi() throws Exception {
        String token = login("admin@test.local", "Dev@12345");

        mockMvc.perform(get("/developer/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/developer/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.roleCounts").exists());
    }

    @Test
    void facultyRoleIsRejectedFromDeveloperApi() throws Exception {
        String token = login(facultyEmail, facultyPassword);

        mockMvc.perform(get("/developer/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/developer/accounts/search").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/developer/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/developer/departments").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/developer/audit-logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"email\":\"x@y.com\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/developer/departments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked Dept\",\"code\":\"HACK\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void profileEndpointCannotBeUsedToEscalateRole() throws Exception {
        String token = login(facultyEmail, facultyPassword);

        mockMvc.perform(put("/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"9999999999\",\"role\":\"DEVELOPER\",\"isActive\":true}"))
                .andExpect(status().isOk());

        User refreshed = userRepository.findByEmail(facultyEmail).orElseThrow();
        assertThat(refreshed.getRole()).isEqualTo(Role.FACULTY);
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "admin@test.local", "password", "wrong-password"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void repeatedFailedLoginsAreTemporarilyBlocked() throws Exception {
        // brute force protection kicks in after MAX_FAILURES wrong passwords
        for (int i = 0; i < com.college.attendance.service.AuthService.MAX_FAILURES; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", facultyEmail, "password", "wrong-" + i))))
                    .andExpect(status().is4xxClientError());
        }

        // even the CORRECT password is rejected during the cool-down
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", facultyEmail, "password", facultyPassword))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Too many failed login attempts")));
    }

    @Test
    void developerCannotChangeOwnRoleOrDeactivateSelf() throws Exception {
        String token = login("admin@test.local", "Dev@12345");
        User admin = userRepository.findByEmail("admin@test.local").orElseThrow();

        mockMvc.perform(put("/developer/accounts/" + admin.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"FACULTY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot change your own role"));

        mockMvc.perform(post("/developer/accounts/" + admin.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot deactivate your own account"));

        User refreshed = userRepository.findByEmail("admin@test.local").orElseThrow();
        assertThat(refreshed.getRole()).isEqualTo(Role.DEVELOPER);
        assertThat(refreshed.getIsActive()).isTrue();
    }

    @Test
    void developerCanUnlockAccount() throws Exception {
        User faculty = userRepository.findByEmail(facultyEmail).orElseThrow();

        // 1. Lock the account with MAX_FAILURES
        for (int i = 0; i < com.college.attendance.service.AuthService.MAX_FAILURES; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", facultyEmail, "password", "wrong-" + i))))
                    .andExpect(status().is4xxClientError());
        }

        // Verify account is locked
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", facultyEmail, "password", facultyPassword))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Too many failed login attempts")));

        // 2. Unlock as developer
        String devToken = login("admin@test.local", "Dev@12345");
        long logsBefore = auditLogRepository.countByAction("ACCOUNT_UNLOCKED");

        mockMvc.perform(post("/developer/accounts/" + faculty.getId() + "/unlock")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Account unlocked successfully"))
                .andExpect(jsonPath("$.account.email").value(facultyEmail));

        assertThat(auditLogRepository.countByAction("ACCOUNT_UNLOCKED")).isGreaterThan(logsBefore);

        // 3. Faculty can immediately log in now
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", facultyEmail, "password", facultyPassword))))
                .andExpect(status().isOk());
    }

    @Test
    void nonDeveloperCannotUnlockAccount() throws Exception {
        User faculty = userRepository.findByEmail(facultyEmail).orElseThrow();
        String facultyToken = login(facultyEmail, facultyPassword);

        mockMvc.perform(post("/developer/accounts/" + faculty.getId() + "/unlock")
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotUnlockNonExistentAccount() throws Exception {
        String devToken = login("admin@test.local", "Dev@12345");

        mockMvc.perform(post("/developer/accounts/99999999/unlock")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Account not found with id: 99999999")));
    }

    @Test
    void successfulPasswordResetUnlocksAccount() throws Exception {
        User faculty = userRepository.findByEmail(facultyEmail).orElseThrow();

        // 1. Lock the account
        for (int i = 0; i < com.college.attendance.service.AuthService.MAX_FAILURES; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", facultyEmail, "password", "wrong-" + i))))
                    .andExpect(status().is4xxClientError());
        }

        // Verify account is locked
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", facultyEmail, "password", facultyPassword))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Too many failed login attempts")));

        // 2. Initiate password reset via developer endpoint
        String devToken = login("admin@test.local", "Dev@12345");
        mockMvc.perform(post("/developer/accounts/" + faculty.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        String resetToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        assertThat(resetToken).isNotNull();

        // 3. Reset password through setup-password endpoint
        String newPassword = "NewFacultyPass@123";
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", resetToken,
                                "newPassword", newPassword,
                                "confirmPassword", newPassword
                        ))))
                .andExpect(status().isOk());

        // 4. Verify login succeeds with new password (lockout was cleared)
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", facultyEmail, "password", newPassword))))
                .andExpect(status().isOk());
    }
}
