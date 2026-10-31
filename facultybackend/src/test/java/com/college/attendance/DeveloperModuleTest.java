package com.college.attendance;

import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.AttendanceStatus;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.AuditLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.security.SecureTokens;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Functional coverage for the Developer / Super Admin module:
 * account creation, activation tokens, deactivation, dashboard statistics,
 * audit logging, pagination/filtering and department management.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class DeveloperModuleTest {

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StubEmailConfiguration.StubEmailService emailService;

    private String developerToken;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        StubEmailConfiguration.StubEmailService.reset();
        developerToken = login("admin@test.local", "Dev@12345");
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

    private JsonNode createAccount(String email, Role role, Long departmentId) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("firstName", "Test");
        payload.put("lastName", "User");
        payload.put("email", email);
        payload.put("role", role.name());
        if (departmentId != null) {
            payload.put("departmentId", departmentId);
        }

        if (role == Role.FACULTY) {
            byte[] bytes = new byte[100];
            bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF; bytes[3] = (byte) 0xE0;
            MvcResult result = mockMvc.perform(multipart("/developer/accounts")
                            .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", bytes))
                            .file(new MockMultipartFile("request", "", "application/json", objectMapper.writeValueAsBytes(payload)))
                            .header("Authorization", "Bearer " + developerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString());
        }

        MvcResult result = mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // =========================================================
    // ACCOUNT CREATION
    // =========================================================

    @Test
    void createdAccountIsPendingWithHashedSingleUseTokenAndAudited() throws Exception {
        String email = "create." + suffix + "@test.local";

        JsonNode response = createAccount(email, Role.FACULTY, null);

        assertThat(response.get("emailSent").asBoolean()).isTrue();
        assertThat(response.get("activationStatus").asText()).isEqualTo("PENDING");
        assertThat(response.get("message").asText()).contains("Activation email sent");

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getIsActive()).isFalse();                       // pending activation
        assertThat(user.getPasswordSetupToken()).isNotNull();
        // only the SHA-256 hash is stored (64 hex chars), never a raw token
        assertThat(user.getPasswordSetupToken()).matches("[0-9a-f]{64}");
        assertThat(user.getPasswordSetupTokenExpiry()).isAfter(LocalDateTime.now());

        // audit trail entry
        MvcResult audit = mockMvc.perform(get("/developer/audit-logs")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String auditJson = audit.getResponse().getContentAsString();
        assertThat(auditJson).contains("ACCOUNT_CREATED").contains(email);
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = "dup." + suffix + "@test.local";
        createAccount(email, Role.FACULTY, null);

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + email + "\",\"role\":\"HOD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already registered")));
    }

    @Test
    void accountCreationReportsEmailFailureInsteadOfFakeSuccess() throws Exception {
        emailService.setupEmailResult = false;
        String email = "mailfail." + suffix + "@test.local";

        JsonNode response = createAccount(email, Role.FACULTY, null);

        assertThat(response.get("emailSent").asBoolean()).isFalse();
        assertThat(response.get("message").asText()).contains("could NOT be sent");
        // account still exists, pending, ready for "Resend activation"
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordSetupToken()).isNotNull();
    }

    // =========================================================
    // ACTIVATION FLOW
    // =========================================================

    @Test
    void setupPasswordActivatesAccountAndTokenIsSingleUse() throws Exception {
        String email = "activate." + suffix + "@test.local";
        String rawToken = SecureTokens.generate();

        User user = userRepository.save(User.builder()
                .employeeId("ACT" + suffix)
                .firstName("Act")
                .lastName("Ivation")
                .email(email)
                .passwordHash(passwordEncoder.encode("placeholder-x"))
                .role(Role.FACULTY)
                .isActive(false)
                .firstLogin(true)
                .passwordSetupToken(SecureTokens.sha256(rawToken))
                .passwordSetupTokenExpiry(LocalDateTime.now().plusHours(24))
                .build());

        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"NewPass1a\",\"confirmPassword\":\"NewPass1a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("successfully")));

        User activated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(activated.getIsActive()).isTrue();                 // activated only after password setup
        assertThat(activated.getPasswordSetupToken()).isNull();       // single use
        assertThat(passwordEncoder.matches("NewPass1a", activated.getPasswordHash())).isTrue();

        // token can no longer be validated or reused
        mockMvc.perform(get("/auth/setup-password/validate").param("token", rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"Another1a\",\"confirmPassword\":\"Another1a\"}"))
                .andExpect(status().isBadRequest());

        // the user can now log in with the password they chose
        String token = login(email, "NewPass1a");
        assertThat(token).isNotBlank();

        // activation confirmation email was attempted
        assertThat(emailService.confirmationEmailCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void expiredAndInvalidTokensAreRejected() throws Exception {
        String email = "expired." + suffix + "@test.local";
        String rawToken = SecureTokens.generate();

        User user = userRepository.save(User.builder()
                .employeeId("EXP" + suffix)
                .firstName("Ex")
                .lastName("Pired")
                .email(email)
                .passwordHash(passwordEncoder.encode("placeholder-x"))
                .role(Role.FACULTY)
                .isActive(false)
                .passwordSetupToken(SecureTokens.sha256(rawToken))
                .passwordSetupTokenExpiry(LocalDateTime.now().minusHours(1))
                .build());

        mockMvc.perform(get("/auth/setup-password/validate").param("token", rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"NewPass1a\",\"confirmPassword\":\"NewPass1a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getIsActive()).isFalse();

        // completely unknown token
        mockMvc.perform(get("/auth/setup-password/validate").param("token", "not-a-real-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\",\"newPassword\":\"NewPass1a\",\"confirmPassword\":\"NewPass1a\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendActivationIssuesNewTokenAndReportsDeliveryOutcome() throws Exception {
        String email = "resend." + suffix + "@test.local";
        createAccount(email, Role.FACULTY, null);
        User user = userRepository.findByEmail(email).orElseThrow();
        String firstHash = user.getPasswordSetupToken();

        // success path
        MvcResult ok = mockMvc.perform(post("/developer/accounts/" + user.getId() + "/resend-activation")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true))
                .andReturn();
        assertThat(ok.getResponse().getContentAsString()).contains("Activation email sent");

        User afterResend = userRepository.findById(user.getId()).orElseThrow();
        assertThat(afterResend.getPasswordSetupToken()).isNotEqualTo(firstHash);
        assertThat(afterResend.getPasswordSetupToken()).matches("[0-9a-f]{64}");
        assertThat(afterResend.getIsActive()).isFalse();

        // failure path — real error instead of fake success
        emailService.setupEmailResult = false;
        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/resend-activation")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.emailSent").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("could NOT be sent")));
    }

    @Test
    void passwordResetIssuesSingleUseLink() throws Exception {
        String email = "reset." + suffix + "@test.local";
        // Password reset is a separate workflow (§13): it only exists for
        // accounts that already completed first-time activation.
        User user = userRepository.save(User.builder()
                .employeeId("RST" + suffix)
                .firstName("Reset")
                .lastName("Ready")
                .email(email)
                .passwordHash(passwordEncoder.encode("Original123"))
                .role(Role.FACULTY)
                .isActive(true)
                .firstLogin(false)
                .build());

        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        User afterReset = userRepository.findById(user.getId()).orElseThrow();
        assertThat(afterReset.getPasswordSetupToken()).matches("[0-9a-f]{64}");
        assertThat(afterReset.getPasswordSetupTokenExpiry()).isAfter(LocalDateTime.now());
        assertThat(afterReset.getPasswordSetupTokenPurpose()).isEqualTo(
                com.college.attendance.enums.TokenPurpose.PASSWORD_RESET);

        long resets = auditLogRepository.countByAction("PASSWORD_RESET_INITIATED");
        assertThat(resets).isGreaterThanOrEqualTo(1);
    }

    @Test
    void pendingAccountCannotBeActivatedWithoutPasswordAndCannotLogin() throws Exception {
        String email = "pending." + suffix + "@test.local";
        createAccount(email, Role.FACULTY, null);
        User user = userRepository.findByEmail(email).orElseThrow();

        // activating a pending account directly must be refused
        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/activate")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Resend")));

        // login is blocked until the user sets a password through the link
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"whatever\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("set your password")));
    }

    // =========================================================
    // DEACTIVATION / ACTIVATION
    // =========================================================

    @Test
    void deactivationPreservesHistoryAndBlocksLogin() throws Exception {
        Department department = departmentRepository.save(Department.builder()
                .name("History Dept " + suffix)
                .code("HDS" + suffix)
                .isActive(true)
                .build());
        String email = "history." + suffix + "@test.local";
        String password = "History123";

        User faculty = userRepository.save(User.builder()
                .employeeId("HIS" + suffix)
                .firstName("His")
                .lastName("Tory")
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.FACULTY)
                .department(department)
                .isActive(true)
                .firstLogin(false)
                .build());

        AttendanceLog log = attendanceLogRepository.save(AttendanceLog.builder()
                .user(faculty)
                .attendanceDate(LocalDate.now())
                .punchInTime(LocalDateTime.now().withHour(9).withMinute(0))
                .status(AttendanceStatus.PRESENT)
                .build());

        mockMvc.perform(delete("/developer/accounts/" + faculty.getId())
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("deactivated")));

        // history preserved
        assertThat(attendanceLogRepository.findById(log.getId())).isPresent();
        assertThat(userRepository.findById(faculty.getId())).isPresent();

        // login blocked
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));

        // reactivate and log in again
        mockMvc.perform(post("/developer/accounts/" + faculty.getId() + "/activate")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());
        login(email, password);
    }

    // =========================================================
    // DASHBOARD STATISTICS + AUDIT
    // =========================================================

    @Test
    void statsAreComputedFromDatabase() throws Exception {
        String email = "stats." + suffix + "@test.local";
        createAccount(email, Role.FACULTY, null);

        MvcResult result = mockMvc.perform(get("/developer/stats")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.roleCounts.FACULTY").exists())
                .andExpect(jsonPath("$.departmentWise").isArray())
                .andExpect(jsonPath("$.recentActions").isArray())
                .andExpect(jsonPath("$.attendance.date").value(LocalDate.now().toString()))
                .andReturn();

        JsonNode stats = objectMapper.readTree(result.getResponse().getContentAsString());
        long total = userRepository.count();

        assertThat(stats.get("total").asLong()).isEqualTo(total);
        assertThat(stats.get("active").asLong()
                + stats.get("inactive").asLong()
                + stats.get("pendingActivations").asLong()).isEqualTo(total);
        assertThat(stats.get("departments").asLong()).isEqualTo(departmentRepository.count());
        assertThat(stats.get("pendingActivations").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("departmentWise").size()).isEqualTo((int) departmentRepository.count());
        assertThat(stats.get("recentActions").size()).isGreaterThanOrEqualTo(1);
    }

    // =========================================================
    // PAGINATION + FILTERING
    // =========================================================

    @Test
    void accountListSupportsBackendPaginationAndFilters() throws Exception {
        String token = suffix;
        createAccount("page1." + token + "@test.local", Role.FACULTY, null);
        createAccount("page2." + token + "@test.local", Role.HOD, null);
        createAccount("page3." + token + "@test.local", Role.FACULTY, null);

        // pagination
        MvcResult page0 = mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("q", token)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();
        JsonNode paged = objectMapper.readTree(page0.getResponse().getContentAsString());
        assertThat(paged.get("totalElements").asLong()).isEqualTo(3);
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
        assertThat(paged.get("first").asBoolean()).isTrue();

        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("q", token)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));

        // role filter
        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("q", token)
                        .param("role", "HOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("HOD"));

        // status filter
        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("q", token)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].activationStatus").value("PENDING"));

        // free-text search by email fragment
        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("q", "page2." + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // sorting param honoured without error
        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("sort", "firstName")
                        .param("direction", "asc")
                        .param("size", "5"))
                .andExpect(status().isOk());

        // invalid filters rejected
        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("status", "WHATEVER"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/developer/accounts/search")
                        .header("Authorization", "Bearer " + developerToken)
                        .param("role", "SUPERWOMAN"))
                .andExpect(status().isBadRequest());

        // legacy endpoint still returns a plain array
        mockMvc.perform(get("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // =========================================================
    // DEPARTMENT MANAGEMENT
    // =========================================================

    @Test
    void departmentLifecycleIsValidatedAndSafe() throws Exception {
        String code = "TMD" + suffix;
        String name = "Test Module Dept " + suffix;

        // create
        MvcResult created = mockMvc.perform(post("/developer/departments")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"description\":\"For tests\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code.toUpperCase()))
                .andReturn();
        long departmentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // duplicate code rejected
        mockMvc.perform(post("/developer/departments")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Another Dept " + suffix + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already in use")));

        // duplicate name rejected
        mockMvc.perform(post("/developer/departments")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"OTH" + suffix + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")));

        // blank values rejected by bean validation
        mockMvc.perform(post("/developer/departments")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"\"}"))
                .andExpect(status().isBadRequest());

        // deactivate via update
        mockMvc.perform(put("/developer/departments/" + departmentId)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));

        // visible in management list, hidden from the active-only list
        mockMvc.perform(get("/developer/departments/all")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + departmentId + ")]").exists());
        mockMvc.perform(get("/developer/departments")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + departmentId + ")]").doesNotExist());

        // department with linked users cannot be deleted (converted to deactivation)
        String email = "deptuser." + suffix + "@test.local";
        User linked = userRepository.save(User.builder()
                .employeeId("DTU" + suffix)
                .firstName("Dept")
                .lastName("User")
                .email(email)
                .passwordHash(passwordEncoder.encode("Dept@12345"))
                .role(Role.FACULTY)
                .department(departmentRepository.findById(departmentId).orElseThrow())
                .isActive(true)
                .firstLogin(false)
                .build());

        mockMvc.perform(delete("/developer/departments/" + departmentId)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("deactivated instead")));

        assertThat(departmentRepository.findById(departmentId)).isPresent();
        assertThat(userRepository.findById(linked.getId())).isPresent();

        // remove the user, then the department can be deleted for real
        userRepository.delete(linked);
        mockMvc.perform(delete("/developer/departments/" + departmentId)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(departmentRepository.findById(departmentId)).isEmpty();
    }
}
