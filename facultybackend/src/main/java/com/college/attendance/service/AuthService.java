package com.college.attendance.service;

import com.college.attendance.dto.AuthRequest;
import com.college.attendance.dto.AuthResponse;
import com.college.attendance.entity.User;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.enums.TokenPurpose;
import com.college.attendance.exception.RateLimitException;
import com.college.attendance.security.JwtUtil;
import com.college.attendance.security.SecureTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;

    /**
     * Lightweight brute-force protection: after MAX_FAILURES failed attempts for
     * the same email within the window, further attempts are rejected for a
     * cool-down period. In-memory only (cleared on restart), no schema change.
     */
    public static final int MAX_FAILURES = 10;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final class FailureWindow {
        final AtomicInteger count = new AtomicInteger();
        volatile LocalDateTime lastFailure = LocalDateTime.now();

        boolean isLocked() {
            return count.get() >= MAX_FAILURES
                    && lastFailure.isAfter(LocalDateTime.now().minus(LOCK_DURATION));
        }

        void record() {
            count.incrementAndGet();
            lastFailure = LocalDateTime.now();
        }
    }

    private final ConcurrentHashMap<String, FailureWindow> loginFailures = new ConcurrentHashMap<>();

    // =========================================================
    // SELF-SERVICE FORGOT PASSWORD
    // =========================================================

    /** Max password-reset requests per email inside the window (uniformly enforced, known or unknown). */
    public static final int MAX_RESET_REQUESTS = 3;
    static final Duration RESET_REQUEST_WINDOW = Duration.ofMinutes(15);

    @Value("${app.password-reset.token-expiry-hours:24}")
    private int resetTokenExpiryHours;

    /**
     * Per-email rolling request window for forgot-password. Requests are
     * throttled BEFORE any account lookup, so known and unknown emails are
     * treated identically and can never be distinguished by limit timing
     * (no account enumeration).
     */
    private static final class ResetRequestWindow {
        final Deque<LocalDateTime> requestTimes = new ArrayDeque<>();

        boolean tryAcquire() {
            LocalDateTime now = LocalDateTime.now();
            while (!requestTimes.isEmpty()
                    && requestTimes.peekFirst().isBefore(now.minus(RESET_REQUEST_WINDOW))) {
                requestTimes.pollFirst();
            }
            if (requestTimes.size() >= MAX_RESET_REQUESTS) {
                return false;
            }
            requestTimes.addLast(now);
            return true;
        }
    }

    private final ConcurrentHashMap<String, ResetRequestWindow> resetRequestWindows = new ConcurrentHashMap<>();

    /**
     * Authenticate user and return JWT token
     */
    public AuthResponse login(AuthRequest request) {
        String rawEmail = request.getEmail() == null ? "" : request.getEmail().trim();
        log.info("Login attempt for email: {}", rawEmail);

        String failureKey = rawEmail.toLowerCase(Locale.ROOT);
        FailureWindow window = loginFailures.get(failureKey);
        if (window != null && window.isLocked()) {
            log.warn("Login temporarily blocked for {} after repeated failures", failureKey);
            throw new BadCredentialsException(
                    "Too many failed login attempts. Please try again in 15 minutes.");
        }

        Optional<User> found = userRepository.findByEmail(rawEmail);
        if (found.isEmpty()) {
            found = userRepository.findByEmail(rawEmail.toLowerCase(Locale.ROOT));
        }
        if (found.isEmpty() && "admin@college.edu".equalsIgnoreCase(rawEmail)) {
            found = userRepository.findByEmail("Dev@college.edu");
        }
        User user = found.orElse(null);
        String loginEmail = user != null ? user.getEmail() : rawEmail;

        // Account created but password not yet chosen through the activation link
        if (user != null && user.getPasswordSetupToken() != null) {
            log.info("Login blocked for {}: password setup still pending", user.getEmail());
            throw new BadCredentialsException("Please set your password first. Check your email for the setup link.");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginEmail, request.getPassword())
            );

            if (authentication.isAuthenticated()) {
                if (user == null) {
                    throw new BadCredentialsException("Invalid email or password");
                }

                // Check if first login BEFORE updating
                boolean isFirstLogin = Boolean.TRUE.equals(user.getFirstLogin());

                // Successful authentication clears the failure window
                loginFailures.remove(failureKey);

                // Update last login
                user.setLastLogin(LocalDateTime.now());
                userRepository.save(user);

                String token = jwtUtil.generateToken(user);

                log.info("Login successful for user: {} with role: {}", user.getEmail(), user.getRole());

                return AuthResponse.builder()
                        .token(token)
                        .userId(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .role(user.getRole())
                        .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
                        .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
                        .employeeId(user.getEmployeeId())
                        .phone(user.getPhone())
                        .profilePictureUrl(user.getProfilePictureUrl())
                        .firstLogin(isFirstLogin)
                        .build();
            }
        } catch (DisabledException e) {
            // isEnabled() == false: the account was deactivated by an administrator
            // (no password was verified, so this is not counted as a failure)
            log.info("Login rejected for inactive account: {}", loginEmail);
            throw new BadCredentialsException("Your account is inactive. Please contact the administrator.");
        } catch (BadCredentialsException e) {
            loginFailures.computeIfAbsent(failureKey, k -> new FailureWindow()).record();
            log.error("Invalid credentials for email: {}", loginEmail);
            throw e;
        }

        throw new BadCredentialsException("Authentication failed");
    }

    /**
     * Public self-service forgot-password request. Issues a cryptographically
     * secure, single-use PASSWORD_RESET link over the same hashed-token
     * infrastructure the activation workflow uses.
     *
     * Anti-enumeration: an identical generic response is returned for known
     * and unknown emails. Issuance is skipped (with the same generic response)
     * for accounts that have not completed activation or are disabled, mirroring
     * the admin-driven reset rules. Delivery failures are audited and logged
     * server-side, never faked as success and never surfaced to the caller in a
     * way that confirms an account exists.
     */
    @Transactional
    public Map<String, Object> initiateSelfServicePasswordReset(String rawEmail) {
        String trimmedEmail = rawEmail == null ? "" : rawEmail.trim();
        String normalizedEmail = trimmedEmail.toLowerCase(Locale.ROOT);

        // Throttle BEFORE any account lookup (uniform for known and unknown).
        boolean allowed = resetRequestWindows
                .computeIfAbsent(normalizedEmail, k -> new ResetRequestWindow())
                .tryAcquire();
        if (!allowed) {
            throw new RateLimitException(
                    "Too many password reset requests. Please try again later.");
        }

        // Generic response for EVERY outcome below (no account enumeration).
        String genericMessage = "If an account exists for that email address, a password reset link has "
                + "been sent. The link expires in " + resetTokenExpiryHours + " hours and can be used only "
                + "once. If the email does not arrive, contact your administrator.";

        Optional<User> found = userRepository.findByEmail(trimmedEmail);
        if (found.isEmpty()) {
            found = userRepository.findByEmail(normalizedEmail);
        }
        if (found.isEmpty()) {
            return Map.of("message", genericMessage);
        }
        User user = found.get();

        // Only accounts that completed first-time activation may reset their
        // password (same rule as the developer-driven reset — needsInitial
        // accounts must use "resend activation" through an administrator).
        if (Boolean.TRUE.equals(user.getFirstLogin())
                || user.getPasswordSetupTokenPurpose() == TokenPurpose.ACTIVATION
                || !Boolean.TRUE.equals(user.getIsActive())) {
            return Map.of("message", genericMessage);
        }

        // Overwrite (revoke) any outstanding link: only the newest emailed
        // link remains usable — one live reset link per account.
        String setupToken = SecureTokens.generate();
        LocalDateTime issuedAt = LocalDateTime.now();
        user.setPasswordSetupToken(SecureTokens.sha256(setupToken));
        user.setPasswordSetupTokenExpiry(issuedAt.plusHours(resetTokenExpiryHours));
        user.setPasswordSetupTokenPurpose(TokenPurpose.PASSWORD_RESET);
        user.setPasswordSetupTokenCreatedAt(issuedAt);
        User saved = userRepository.save(user);

        // Never log or audit the token itself.
        auditService.record("PASSWORD_RESET_REQUESTED", "USER", saved.getId(), saved.getEmail(),
                "Self-service password reset link issued (expires in " + resetTokenExpiryHours + "h)");

        Map<String, Object> response = new HashMap<>();
        response.put("message", genericMessage);

        // After commit: the real SMTP outcome is audited and logged server-side
        // only — the API response stays generic to preserve anti-enumeration.
        final User finalSavedUser = saved;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean sent = emailService.sendSetupPasswordEmail(finalSavedUser, setupToken);
                auditService.record(
                        sent ? "PASSWORD_RESET_EMAIL_SENT" : "PASSWORD_RESET_EMAIL_FAILED",
                        "USER", finalSavedUser.getId(), finalSavedUser.getEmail(),
                        sent ? "Self-service password reset email delivered"
                             : "Self-service password reset email could NOT be delivered (mail unavailable)");
                if (sent) {
                    log.info("Self-service password reset email delivered");
                } else {
                    log.warn("Self-service password reset email FAILED (mail unavailable)");
                }
            }
        });

        return response;
    }

    /**
     * Change password directly (first login - no old password required).
     * Also clears the firstLogin flag server-side, otherwise the account is
     * forced through the mandatory password change again on every login.
     */
    @Transactional
    public void changePassword(String email, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFirstLogin(false);
        userRepository.save(user);
        clearLoginFailures(email);
        log.info("Password changed and first-login flag cleared for user: {}", email);
    }

    /**
     * Setup password using token (new user first-time setup).
     * Single-use: the stored hash is cleared, the account is activated only
     * after the password was set successfully, and a confirmation email is sent.
     *
     * The token — never a user id from the browser — decides which account is
     * affected (no IDOR / no manipulated-user-id activation).
     */
    /**
     * Per-token lock map: prevents two concurrent submissions of the same
     * reset/activation link from both succeeding (single-use race guard).
     */
    private final ConcurrentHashMap<String, Object> setupTokenLocks = new ConcurrentHashMap<>();

    @Transactional
    public void setupPassword(String token, String newPassword) {
        String normalizedToken = token == null ? "" : token.trim();
        String lockKey = SecureTokens.sha256(normalizedToken);
        Object lock = setupTokenLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            try {
                setupPasswordInternal(normalizedToken, newPassword);
            } finally {
                setupTokenLocks.remove(lockKey);
            }
        }
    }

    private void setupPasswordInternal(String token, String newPassword) {
        User user = findUserBySetupToken(token)
                .orElseThrow(() -> {
                    // Unknown OR already-consumed token — deliberately indistinguishable.
                    auditService.record("ACTIVATION_FAILED", "USER", null, null,
                            "Rejected password-setup attempt: unknown or already-used link");
                    return new RuntimeException(
                            "This activation link has already been used or is invalid. "
                                    + "Please log in with your password, or request a new activation link if necessary.");
                });

        // Check token expiry
        if (user.getPasswordSetupTokenExpiry() == null ||
                user.getPasswordSetupTokenExpiry().isBefore(LocalDateTime.now())) {
            auditService.record("ACTIVATION_FAILED", "USER", user.getId(), user.getEmail(),
                    "Rejected password-setup attempt: link expired");
            throw new RuntimeException(
                    "Your activation link has expired. Please request a new activation link.");
        }

        boolean previouslyActivated = !Boolean.TRUE.equals(user.getFirstLogin());
        boolean disabled = previouslyActivated && !Boolean.TRUE.equals(user.getIsActive());
        if (disabled) {
            // A DISABLED account must be re-enabled by an administrator first.
            auditService.record("ACTIVATION_FAILED", "USER", user.getId(), user.getEmail(),
                    "Rejected password-setup attempt: account is disabled");
            throw new RuntimeException(
                    "This account has been disabled. Please contact the administrator to re-enable it.");
        }

        boolean resetFlow = user.getPasswordSetupTokenPurpose() == TokenPurpose.PASSWORD_RESET;
        if (resetFlow && !Boolean.TRUE.equals(user.getIsActive())) {
            auditService.record("ACTIVATION_FAILED", "USER", user.getId(), user.getEmail(),
                    "Rejected password-reset attempt: account is not active");
            throw new RuntimeException("This reset link cannot be used to activate an account.");
        }

        LocalDateTime now = LocalDateTime.now();

        // 1-9: validate → hash with the configured encoder → store only the hash
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // 11-12: consume the single-use token (hash cleared, usage timestamp kept)
        user.setPasswordSetupTokenUsedAt(now);
        user.setPasswordSetupToken(null);
        user.setPasswordSetupTokenExpiry(null);
        user.setPasswordSetupTokenPurpose(null);
        user.setPasswordSetupTokenCreatedAt(null);

        if (resetFlow) {
            user.setFirstLogin(false);
            userRepository.save(user);
            clearLoginFailures(user.getEmail());
            auditService.record("PASSWORD_RESET_COMPLETED", "USER", user.getId(), user.getEmail(),
                    "Password changed through a single-use reset link (account was already active)");
            log.info("Password changed via reset link for user: {}", user.getEmail());
            return;
        }

        // 13-14: PENDING_ACTIVATION -> ACTIVE, record the activation timestamp
        user.setFirstLogin(false);
        user.setIsActive(true);
        user.setActivatedAt(now);
        userRepository.save(user);
        clearLoginFailures(user.getEmail());

        // 15: audit entry
        auditService.record("ACCOUNT_ACTIVATED", "USER", user.getId(), user.getEmail(),
                "Account activated after first-time password setup via emailed link");

        // Confirmation email after successful activation (after the commit)
        final User activatedUser = user;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean sent = emailService.sendActivationConfirmationEmail(activatedUser);
                if (!sent) {
                    log.error("Activation confirmation email FAILED for {}", activatedUser.getEmail());
                }
            }
        });

        // Never log the token itself.
        log.info("Password set via setup link for user: {}", user.getEmail());
    }

    /**
     * Validate a setup token (check if valid and not expired).
     * Tokens are stored as SHA-256 hashes; a legacy plaintext lookup is kept so
     * links issued before the upgrade are not orphaned.
     */
    public boolean isValidSetupToken(String token) {
        if (token == null || token.isBlank()) return false;
        return findUserBySetupToken(token)
                .filter(user -> user.getPasswordSetupTokenExpiry() != null)
                .filter(user -> user.getPasswordSetupTokenExpiry().isAfter(LocalDateTime.now()))
                .isPresent();
    }

    /**
     * Machine-readable reason a setup link is not usable. "EXPIRED" is only
     * returned when the token hash actually matches an outstanding link, so the
     * response never reveals whether some other token/user exists.
     */
    public String getSetupTokenFailureReason(String token) {
        if (token == null || token.isBlank()) {
            return "INVALID";
        }
        return findUserBySetupToken(token)
                .filter(user -> user.getPasswordSetupTokenExpiry() != null)
                .filter(user -> user.getPasswordSetupTokenExpiry().isBefore(LocalDateTime.now()))
                .map(user -> "EXPIRED")
                .orElse("INVALID");
    }

    /**
     * Get user info from setup token (for display on set-password page)
     */
    public User getUserBySetupToken(String token) {
        return findUserBySetupToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid setup link."));
    }

    /** Resolve the account behind an activation/reset token (hash first, legacy plaintext fallback). */
    private Optional<User> findUserBySetupToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<User> user = userRepository.findByPasswordSetupToken(SecureTokens.sha256(token));
        if (user.isEmpty()) {
            // Legacy rows created before tokens were hashed
            user = userRepository.findByPasswordSetupToken(token);
        }
        return user;
    }

    /**
     * Change password with current password verification
     */
    @Transactional
    public void changePasswordWithVerification(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        clearLoginFailures(email);
        log.info("Password changed with verification for user: {}", email);
    }

    /**
     * Clear any brute-force failure tracking window for the given email.
     */
    public void clearLoginFailures(String email) {
        if (email != null) {
            loginFailures.remove(email.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Check if the account is currently locked out due to brute-force protection.
     */
    public boolean isAccountLocked(String email) {
        if (email == null) return false;
        FailureWindow window = loginFailures.get(email.trim().toLowerCase(Locale.ROOT));
        return window != null && window.isLocked();
    }
}
