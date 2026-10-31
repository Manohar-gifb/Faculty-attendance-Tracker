package com.college.attendance;

import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.enums.TokenPurpose;
import com.college.attendance.repository.AuditLogRepository;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * First-time account activation / password setup workflow.
 *
 * Covers the requirements that are not already asserted by
 * {@link DeveloperModuleTest} and {@link DeveloperSecurityTest}:
 * PENDING_ACTIVATION status, hashed-only token storage, purpose separation
 * (activation vs reset), resend revoking the previous link, disabled accounts
 * unable to activate, expired vs invalid messaging, IDOR safety, rate limiting,
 * activation audit trail and the post-activation attendance workflow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class ActivationWorkflowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StubEmailConfiguration.StubEmailService emailService;
    @Autowired private com.college.attendance.service.FaceVerificationService faceVerificationService;

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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private User createAccount(String email, Role role) throws Exception {
        if (role == Role.FACULTY) {
            byte[] bytes = new byte[100];
            bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF; bytes[3] = (byte) 0xE0;
            mockMvc.perform(multipart("/developer/accounts")
                            .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", bytes))
                            .file(new MockMultipartFile("request", "", "application/json",
                                    ("{\"firstName\":\"First\",\"lastName\":\"Time\",\"email\":\"" + email
                                            + "\",\"role\":\"" + role.name() + "\"}").getBytes()))
                            .header("Authorization", "Bearer " + developerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activationStatus").value("PENDING"));
            return userRepository.findByEmail(email).orElseThrow();
        }
        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"First\",\"lastName\":\"Time\",\"email\":\"" + email
                                + "\",\"role\":\"" + role.name() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activationStatus").value("PENDING"));
        return userRepository.findByEmail(email).orElseThrow();
    }

    private String setupPassword(String token, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password
                                + "\",\"confirmPassword\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asText();
    }

    // =========================================================
    // CREATION → PENDING_ACTIVATION, hashed token only
    // =========================================================

    @Test
    void accountIsCreatedPendingWithHashedSingleUseActivationToken() throws Exception {
        String email = "act." + suffix + "@test.local";

        User user = createAccount(email, Role.FACULTY);

        // §1 status = PENDING_ACTIVATION, login not possible yet
        assertThat(user.getIsActive()).isFalse();
        assertThat(user.getFirstLogin()).isTrue();
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        assertThat(rawToken).isNotBlank();

        // §2 only the hash is stored, plus purpose / creation time / expiry
        assertThat(user.getPasswordSetupToken()).matches("[0-9a-f]{64}");
        assertThat(user.getPasswordSetupToken()).isNotEqualTo(rawToken);
        assertThat(user.getPasswordSetupToken()).isEqualTo(SecureTokens.sha256(rawToken));
        assertThat(user.getPasswordSetupTokenPurpose()).isEqualTo(TokenPurpose.ACTIVATION);
        assertThat(user.getPasswordSetupTokenCreatedAt()).isNotNull();
        assertThat(user.getPasswordSetupTokenExpiry()).isAfter(LocalDateTime.now());
        assertThat(user.getPasswordSetupTokenUsedAt()).isNull();
        assertThat(user.getActivatedAt()).isNull();

        // §3 the email was actually generated (no password inside)
        assertThat(emailService.setupEmailCount).isEqualTo(1);
        assertThat(emailService.lastSetupRecipient).isEqualTo(email);

        // §5 login blocked before activation
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Whatever1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("set your password")));

        // audit: account created + activation email delivered
        assertThat(auditLogRepository.countByAction("ACCOUNT_CREATED")).isGreaterThanOrEqualTo(1);
        assertThat(auditLogRepository.countByAction("ACTIVATION_EMAIL_SENT")).isGreaterThanOrEqualTo(1);
    }

    // =========================================================
    // FULL ACTIVATION → ACTIVE → LOGIN → NO PRIVILEGE ESCALATION
    // =========================================================

    @Test
    void validTokenActivatesAccountAndUserGetsOnlyTheirOwnPermissions() throws Exception {
        String email = "full." + suffix + "@test.local";
        String password = "Activ@ted1";
        User user = createAccount(email, Role.FACULTY);
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        setupPassword(rawToken, password);

        User activated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(activated.getIsActive()).isTrue();
        assertThat(activated.getFirstLogin()).isFalse();
        assertThat(activated.getActivatedAt()).isNotNull();
        assertThat(activated.getPasswordSetupToken()).isNull();
        assertThat(activated.getPasswordSetupTokenPurpose()).isNull();
        assertThat(activated.getPasswordSetupTokenUsedAt()).isNotNull();
        assertThat(passwordEncoder.matches(password, activated.getPasswordHash())).isTrue();
        assertThat(activated.getPasswordHash()).startsWith("$2");

        // confirmation email attempted
        assertThat(emailService.confirmationEmailCount).isGreaterThanOrEqualTo(1);

        // §7 login with the password the user created
        String userToken = login(email, password);

        // §16 the token itself decides the account — a browser-supplied userId is ignored
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"Hacked999a\","
                                + "\"confirmPassword\":\"Hacked999a\",\"userId\":1}"))
                .andExpect(status().isBadRequest());

        // still the original password, developer account untouched
        assertThat(passwordEncoder.matches(password,
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("Hacked999a",
                userRepository.findById(1L).orElseThrow().getPasswordHash())).isFalse();

        // §20 attendance workflow still works for the activated faculty user
        // (punch-in now requires a single-use verification ticket from
        // /attendance/verify — without one it is rejected)
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());

        String ticket = faceVerificationService.issueTicket(user.getId());
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + ticket + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/attendance/today")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()));

        // §9/§18 no developer permissions for a faculty account
        mockMvc.perform(get("/developer/stats")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        assertThat(auditLogRepository.countByAction("ACCOUNT_ACTIVATED")).isGreaterThanOrEqualTo(1);
    }

    // =========================================================
    // SINGLE-USE / EXPIRED / INVALID
    // =========================================================

    @Test
    void expiredLinkIsRejectedWithClearMessageAndReason() throws Exception {
        String email = "exp." + suffix + "@test.local";
        User user = createAccount(email, Role.FACULTY);
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        user.setPasswordSetupTokenExpiry(LocalDateTime.now().minusHours(1));
        userRepository.save(user);

        mockMvc.perform(get("/auth/setup-password/validate").param("token", rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("EXPIRED"));

        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"Expired1a\","
                                + "\"confirmPassword\":\"Expired1a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("expired")));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(auditLogRepository.countByAction("ACTIVATION_FAILED")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void invalidAndAlreadyUsedLinksAreIndistinguishableAndRejected() throws Exception {
        String email = "reuse." + suffix + "@test.local";
        User user = createAccount(email, Role.FACULTY);
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        setupPassword(rawToken, "FirstPass1");

        // used link
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"Second99a\","
                                + "\"confirmPassword\":\"Second99a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already been used")));

        // unknown link — same class of answer, no user/database details
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\", \"newPassword\":\"Second99a\","
                                + "\"confirmPassword\":\"Second99a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already been used")));

        mockMvc.perform(get("/auth/setup-password/validate").param("token", "not-a-real-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("INVALID"))
                .andExpect(jsonPath("$.email").doesNotExist());

        // account stays ACTIVE with the first password
        User after = userRepository.findById(user.getId()).orElseThrow();
        assertThat(after.getIsActive()).isTrue();
        assertThat(passwordEncoder.matches("FirstPass1", after.getPasswordHash())).isTrue();
    }

    // =========================================================
    // RESEND INVALIDATES THE PREVIOUS LINK
    // =========================================================

    @Test
    void resendingActivationRevokesThePreviousLinkAndOnlyOneWorks() throws Exception {
        String email = "resend." + suffix + "@test.local";
        User user = createAccount(email, Role.FACULTY);
        String firstToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/resend-activation")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        String secondToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        assertThat(secondToken).isNotEqualTo(firstToken);

        User reissued = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reissued.getPasswordSetupToken()).isEqualTo(SecureTokens.sha256(secondToken));
        assertThat(reissued.getPasswordSetupTokenPurpose()).isEqualTo(TokenPurpose.ACTIVATION);
        assertThat(reissued.getIsActive()).isFalse();

        // old link no longer works
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + firstToken + "\",\"newPassword\":\"OldToken1a\","
                                + "\"confirmPassword\":\"OldToken1a\"}"))
                .andExpect(status().isBadRequest());

        // new link works and there is never more than one valid link
        setupPassword(secondToken, "NewToken1a");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getIsActive()).isTrue();
        assertThat(auditLogRepository.countByAction("ACTIVATION_RESENT")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resendActivationIsRateLimited() throws Exception {
        String email = "throttle." + suffix + "@test.local";
        User user = createAccount(email, Role.FACULTY);

        // default limit is 5 sends per account per 10-minute window
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/developer/accounts/" + user.getId() + "/resend-activation")
                            .header("Authorization", "Bearer " + developerToken))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/resend-activation")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Too many emails")));
    }

    // =========================================================
    // DISABLED ACCOUNTS
    // =========================================================

    @Test
    void disabledAccountCannotActivateThroughAnOldLink() throws Exception {
        String email = "disabled." + suffix + "@test.local";
        User user = createAccount(email, Role.FACULTY);
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        // administrator disables the account while the link is outstanding
        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk());

        User disabled = userRepository.findById(user.getId()).orElseThrow();
        assertThat(disabled.getIsActive()).isFalse();
        // the outstanding link was revoked with the account
        assertThat(disabled.getPasswordSetupToken()).isNull();
        assertThat(disabled.getPasswordSetupTokenPurpose()).isNull();

        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"Disabled1a\","
                                + "\"confirmPassword\":\"Disabled1a\"}"))
                .andExpect(status().isBadRequest());

        User stillDisabled = userRepository.findById(user.getId()).orElseThrow();
        assertThat(stillDisabled.getIsActive()).isFalse();
        assertThat(stillDisabled.getPasswordHash()).isNotEqualTo(passwordEncoder.encode("Disabled1a"));

        // login stays blocked as well
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Disabled1a\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §13 ACTIVATION AND PASSWORD RESET ARE SEPARATE WORKFLOWS
    // =========================================================

    @Test
    void activationAndResetTokensCannotBeInterchanged() throws Exception {
        String email = "separate." + suffix + "@test.local";
        User user = createAccount(email, Role.FACULTY);
        String activationToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        // a PENDING account has no password-reset workflow yet
        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Resend activation")));

        // activate first
        setupPassword(activationToken, "Act1vePass1");
        assertThat(userRepository.findById(user.getId()).orElseThrow()
                .getPasswordSetupTokenPurpose()).isNull();

        // now a reset link can be issued — stored with a different purpose
        mockMvc.perform(post("/developer/accounts/" + user.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        User withReset = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withReset.getPasswordSetupTokenPurpose()).isEqualTo(TokenPurpose.PASSWORD_RESET);
        assertThat(auditLogRepository.countByAction("PASSWORD_RESET_INITIATED")).isGreaterThanOrEqualTo(1);

        String resetToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        long activationsBefore = auditLogRepository.countByAction("ACCOUNT_ACTIVATED");

        // consuming the reset link changes the password WITHOUT re-activating
        setupPassword(resetToken, "ResetPass1a");
        User afterReset = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("ResetPass1a", afterReset.getPasswordHash())).isTrue();
        assertThat(afterReset.getIsActive()).isTrue();
        assertThat(afterReset.getPasswordSetupToken()).isNull();
        assertThat(auditLogRepository.countByAction("PASSWORD_RESET_COMPLETED")).isGreaterThanOrEqualTo(1);
        assertThat(auditLogRepository.countByAction("ACCOUNT_ACTIVATED")).isEqualTo(activationsBefore);

        // and the old activation token still cannot be replayed afterwards
        mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + activationToken + "\",\"newPassword\":\"Replayed1a\","
                                + "\"confirmPassword\":\"Replayed1a\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §14 EMAIL FAILURE KEEPS THE ACCOUNT PENDING, NO FAKE SUCCESS
    // =========================================================

    @Test
    void emailFailureKeepsAccountPendingAndIsAudited() throws Exception {
        emailService.setupEmailResult = false;
        String email = "mailfail." + suffix + "@test.local";

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Mail\",\"lastName\":\"Fail\",\"email\":\"" + email
                                + "\",\"role\":\"HOD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("could NOT be sent")));

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getIsActive()).isFalse();
        assertThat(user.getPasswordSetupToken()).isNotNull();   // ready for a resend
        assertThat(auditLogRepository.countByAction("ACTIVATION_EMAIL_FAILED")).isGreaterThanOrEqualTo(1);
    }
}
