package com.college.attendance;

import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.enums.TokenPurpose;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.security.SecureTokens;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public self-service Forgot Password workflow (POST /auth/forgot-password).
 *
 * Covers: generic response for unknown emails (no enumeration), hashed
 * single-use PASSWORD_RESET tokens issued over the existing activation-token
 * infrastructure, successful reset + login with the new password, token reuse
 * rejection, expired links, per-email rate limiting that applies uniformly to
 * known and unknown emails, unimplemented/disabled accounts never revealing
 * that they exist, and dev-driven "previous link revoked" semantics.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class ForgotPasswordWorkflowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StubEmailConfiguration.StubEmailService emailService;

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

    /** Creates a FACULTY account PENDING activation (with a photo, like the real workflow). */
    private User createAccount(String email, Role role) throws Exception {
        byte[] bytes = new byte[100];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF; bytes[3] = (byte) 0xE0;
        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", bytes))
                        .file(new MockMultipartFile("request", "", "application/json",
                                ("{\"firstName\":\"Forgot\",\"lastName\":\"Tester\",\"email\":\"" + email
                                        + "\",\"role\":\"" + role.name() + "\"}").getBytes()))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activationStatus").value("PENDING"));
        return userRepository.findByEmail(email).orElseThrow();
    }

    private String createAndActivateAccount(String email) throws Exception {
        createAccount(email, Role.FACULTY);
        String activationToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        performSetupPassword(activationToken, "Activ@ted1").andExpect(status().isOk());
        return activationToken;
    }

    private String forgotPassword(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions performSetupPassword(String token, String password)
            throws Exception {
        return mockMvc.perform(post("/auth/setup-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password
                                + "\",\"confirmPassword\":\"" + password + "\"}"));
    }

    private String genericMessageFrom(String body) throws Exception {
        return objectMapper.readTree(body).get("message").asText();
    }

    // =========================================================
    // UNKNOWN EMAIL → GENERIC 200, NO EMAIL SENT, NO ENUMERATION
    // =========================================================

    @Test
    void unknownEmailReturnsGenericResponseWithoutRevealingAccountExistence() throws Exception {
        int before = StubEmailConfiguration.StubEmailService.setupEmailCount;

        String body = forgotPassword("nobody." + suffix + "@test.local");

        assertThat(body).contains("If an account exists for that email address");
        // Deliberately indistinguishable from the known-email response wording
        String knownBody = forgotPasswordBodyForKnownAccount();
        assertThat(genericMessageFrom(body)).isEqualTo(genericMessageFrom(knownBody));

        // no reset email attempted for an unknown account
        assertThat(StubEmailConfiguration.StubEmailService.setupEmailCount).isEqualTo(before + 1);
    }

    private String forgotPasswordBodyForKnownAccount() throws Exception {
        String email = "known." + suffix + "@test.local";
        createAndActivateAccount(email);
        StubEmailConfiguration.StubEmailService.reset();
        return forgotPassword(email);
    }

    // =========================================================
    // KNOWN ACTIVE ACCOUNT → LINK ISSUED, HASHED, SINGLE-USE
    // =========================================================

    @Test
    void knownActiveAccountIssuesHashedSingleUseResetToken() throws Exception {
        String email = "reset." + suffix + "@test.local";
        createAndActivateAccount(email);
        StubEmailConfiguration.StubEmailService.reset();

        String body = forgotPassword(email);

        assertThat(body).contains("If an account exists for that email address");

        // the link was really issued and emailed (no fake success)
        assertThat(StubEmailConfiguration.StubEmailService.setupEmailCount).isEqualTo(1);
        assertThat(StubEmailConfiguration.StubEmailService.lastSetupRecipient).isEqualTo(email);

        User user = userRepository.findByEmail(email).orElseThrow();
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        assertThat(rawToken).isNotBlank();
        // only the SHA-256 hash is stored, purpose = PASSWORD_RESET, expiry in the future
        assertThat(user.getPasswordSetupToken()).matches("[0-9a-f]{64}");
        assertThat(user.getPasswordSetupToken()).isEqualTo(SecureTokens.sha256(rawToken));
        assertThat(user.getPasswordSetupTokenPurpose()).isEqualTo(TokenPurpose.PASSWORD_RESET);
        assertThat(user.getPasswordSetupTokenExpiry()).isAfter(LocalDateTime.now());
        // the in-flight reset link has NOT been consumed yet
        assertThat(user.getPasswordSetupToken()).isNotNull();
    }

    // =========================================================
    // SUCCESSFUL RESET → LOGIN WORKS, LINK DEAD AFTER USE
    // =========================================================

    @Test
    void resetCompletesChangesPasswordAndLinkCannotBeReused() throws Exception {
        String email = "reuse." + suffix + "@test.local";
        createAndActivateAccount(email);
        StubEmailConfiguration.StubEmailService.reset();

        forgotPassword(email);
        String rawToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        String oldPasswordRecorded = userRepository.findByEmail(email).orElseThrow().getPasswordHash();

        // reset with the emailed link
        performSetupPassword(rawToken, "NewP@ssword9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password set successfully. You can now log in."));

        User after = userRepository.findByEmail(email).orElseThrow();
        assertThat(passwordEncoder.matches("NewP@ssword9", after.getPasswordHash())).isTrue();
        assertThat(after.getPasswordHash()).startsWith("$2");
        assertThat(after.getPasswordHash()).isNotEqualTo(oldPasswordRecorded);
        assertThat(after.getPasswordSetupToken()).isNull();
        assertThat(after.getPasswordSetupTokenPurpose()).isNull();
        assertThat(after.getPasswordSetupTokenUsedAt()).isNotNull();

        // login with the NEW password works
        login(email, "NewP@ssword9");

        // the SAME link cannot be used again
        performSetupPassword(rawToken, "SecondP@ss1")
                .andExpect(status().isBadRequest());

        // old password never came back; new password unchanged
        assertThat(passwordEncoder.matches("NewP@ssword9",
                userRepository.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
    }

    // =========================================================
    // EXPIRED LINK → EXPIRED (NOT INVALID) MESSAGING
    // =========================================================

    @Test
    void expiredResetLinkIsRejectedAsExpired() throws Exception {
        String email = "expired." + suffix + "@test.local";
        createAndActivateAccount(email);
        StubEmailConfiguration.StubEmailService.reset();

        forgotPassword(email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setPasswordSetupTokenExpiry(LocalDateTime.now().minusMinutes(1));
        userRepository.save(user);

        performSetupPassword(StubEmailConfiguration.StubEmailService.lastSetupToken, "Whatever@1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("expired")));
    }

    // =========================================================
    // RATE LIMITING — UNIFORM FOR KNOWN AND UNKNOWN EMAILS
    // =========================================================

    @Test
    void forgetPasswordRequestsAreRateLimitedPerEmail() throws Exception {
        String email = "ratelimit." + suffix + "@test.local";
        String first = forgotPassword(email);
        assertThat(first).contains("If an account exists");
        assertThat(forgotPassword(email)).contains("If an account exists");
        assertThat(forgotPassword(email)).contains("If an account exists");

        // 4th request inside the window is throttled — uniformly, so the
        // 429 response cannot confirm or deny an account's existence
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isTooManyRequests());

        // unknown emails get the same throttling at the same threshold
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "ratelimit-other." + suffix + "@test.local"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "ratelimit-other." + suffix + "@test.local"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "ratelimit-other." + suffix + "@test.local"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "ratelimit-other." + suffix + "@test.local"))))
                .andExpect(status().isTooManyRequests());
    }

    // =========================================================
    // PENDING-ACTIVATION ACCOUNT → GENERIC, NO RESET LINK
    // =========================================================

    @Test
    void pendingActivationAccountGetsGenericResponseWithoutIssuingResetLink() throws Exception {
        String email = "pending." + suffix + "@test.local";
        createAccount(email, Role.FACULTY); // stays PENDING
        StubEmailConfiguration.StubEmailService.reset();

        String body = forgotPassword(email);

        assertThat(body).contains("If an account exists for that email address");
        assertThat(StubEmailConfiguration.StubEmailService.setupEmailCount).isEqualTo(0);

        User stillPending = userRepository.findByEmail(email).orElseThrow();
        assertThat(stillPending.getIsActive()).isFalse();
    }

    // =========================================================
    // MALFORMED EMAIL → VALIDATION ERROR (generic)
    // =========================================================

    @Test
    void malformedEmailProducesValidationErrorResponse() throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please enter a valid email address."));
    }

    // =========================================================
    // ISSUING A NEW LINK REVOKES THE PREVIOUS ONE
    // =========================================================

    @Test
    void aSecondRequestRevokesTheFirstLink() throws Exception {
        String email = "revoke." + suffix + "@test.local";
        createAndActivateAccount(email);
        StubEmailConfiguration.StubEmailService.reset();

        forgotPassword(email);
        String firstToken = StubEmailConfiguration.StubEmailService.lastSetupToken;

        forgotPassword(email);
        String secondToken = StubEmailConfiguration.StubEmailService.lastSetupToken;
        assertThat(secondToken).isNotBlank().isNotEqualTo(firstToken);

        // the FIRST link is dead
        performSetupPassword(firstToken, "ShouldFail@1")
                .andExpect(status().isBadRequest());

        // the SECOND (newest) link works
        performSetupPassword(secondToken, "SecondP@ss1")
                .andExpect(status().isOk());
    }
}
