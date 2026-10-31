package com.college.attendance.service;

import com.college.attendance.dto.*;
import com.college.attendance.entity.AuditLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.AttendanceStatus;
import com.college.attendance.enums.Role;
import com.college.attendance.enums.TokenPurpose;
import com.college.attendance.exception.RateLimitException;
import com.college.attendance.repository.AuditLogRepository;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.security.SecureTokens;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import com.college.attendance.util.ImageFileValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeveloperService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_PENDING = "PENDING";

    private static final int MAX_PAGE_SIZE = 200;
    private static final int SETUP_TOKEN_EXPIRY_HOURS = 24;

    /**
     * Email-abuse protection for resend-activation / password-reset links.
     * In-memory (cleared on restart) — same style as the login throttle.
     */
    @Value("${app.activation.resend-max-attempts:5}")
    private int resendMaxAttempts;

    @Value("${app.activation.resend-window-minutes:10}")
    private int resendWindowMinutes;

    private final ConcurrentHashMap<Long, Deque<LocalDateTime>> emailSendWindows = new ConcurrentHashMap<>();

    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "firstname", "firstName",
            "lastname", "lastName",
            "name", "firstName",
            "email", "email",
            "employeeid", "employeeId",
            "createdat", "createdAt",
            "role", "role"
    );

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AttendanceLogRepository attendanceLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final FaceVerificationService faceVerificationService;
    private final FileStorageService fileStorageService;
    private final PhotoCleanupService photoCleanupService;
    private final AuthService authService;

    // =========================================================
    // AUTHORIZATION GUARD (defense in depth, on top of SecurityConfig)
    // =========================================================

    /**
     * Ensures the current principal is a DEVELOPER. SecurityConfig already
     * restricts /developer/** to ROLE_DEVELOPER, and the controller carries
     * @PreAuthorize; this service-level check protects against future callers.
     */
    private User requireDeveloper() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AccessDeniedException("Authentication required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            if (user.getRole() != Role.DEVELOPER) {
                throw new AccessDeniedException("Developer role required");
            }
            return user;
        }
        throw new AccessDeniedException("Developer role required");
    }

    // =========================================================
    // ACCOUNTS
    // =========================================================

    /**
     * Get all accounts (without passwords). Legacy contract — returns the full list.
     */
    @Transactional(readOnly = true)
    public List<UserProfileDTO> getAllAccounts() {
        requireDeveloper();
        return userRepository.findAll(Sort.by("firstName")).stream()
                .map(this::mapToProfileDTO)
                .toList();
    }

    /**
     * Backend paginated / sorted / filtered account list.
     */
    @Transactional(readOnly = true)
    public PagedAccountsDTO getAccountsPage(String q, String role, Long departmentId, String status,
                                             String sort, String direction, int page, int size) {
        requireDeveloper();

        Role roleFilter = null;
        if (role != null && !role.isBlank() && !"ALL".equalsIgnoreCase(role)) {
            try {
                roleFilter = Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid role filter: " + role);
            }
        }

        String statusFilter = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : status.trim().toUpperCase(Locale.ROOT);
        if (statusFilter != null && !List.of(STATUS_ACTIVE, STATUS_INACTIVE, STATUS_PENDING).contains(statusFilter)) {
            throw new IllegalArgumentException("Invalid status filter: " + status);
        }

        String sortField = SORT_WHITELIST.getOrDefault(
                (sort == null || sort.isBlank()) ? "createdat" : sort.trim().toLowerCase(Locale.ROOT),
                "createdAt");
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;

        final Role roleFilterFinal = roleFilter;

        Specification<User> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                Join<Object, Object> departmentJoin = root.join("department", JoinType.LEFT);
                List<Predicate> likePredicates = new ArrayList<>();
                likePredicates.add(cb.like(cb.lower(root.get("firstName")), like));
                likePredicates.add(cb.like(cb.lower(root.get("lastName")), like));
                likePredicates.add(cb.like(cb.lower(root.get("email")), like));
                likePredicates.add(cb.like(cb.lower(root.get("employeeId")), like));
                likePredicates.add(cb.like(cb.lower(departmentJoin.get("name")), like));
                try {
                    likePredicates.add(cb.equal(root.get("role"), Role.valueOf(q.trim().toUpperCase(Locale.ROOT))));
                } catch (IllegalArgumentException ignored) {
                    // free text simply does not match a role name
                }
                predicates.add(cb.or(likePredicates.toArray(new Predicate[0])));
            }

            if (roleFilterFinal != null) {
                predicates.add(cb.equal(root.get("role"), roleFilterFinal));
            }
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("department").get("id"), departmentId));
            }
            if (STATUS_PENDING.equals(statusFilter)) {
                predicates.add(cb.isNotNull(root.get("passwordSetupToken")));
            } else if (STATUS_ACTIVE.equals(statusFilter)) {
                predicates.add(cb.isNull(root.get("passwordSetupToken")));
                predicates.add(cb.isTrue(root.get("isActive")));
            } else if (STATUS_INACTIVE.equals(statusFilter)) {
                predicates.add(cb.isNull(root.get("passwordSetupToken")));
                predicates.add(cb.isFalse(root.get("isActive")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Page<User> result = userRepository.findAll(specification,
                PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sortField)));

        return PagedAccountsDTO.builder()
                .content(result.getContent().stream().map(this::mapToProfileDTO).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .first(result.isFirst())
                .last(result.isLast())
                .build();
    }

    /**
     * Create a new account in PENDING state and send the setup-password email.
     * The response reports whether the email was actually delivered.
     */
    @Transactional
    public AccountCreateResult createAccount(AccountCreateRequest request, MultipartFile photo) {
        requireDeveloper();

        String email = request.getEmail() == null ? null : request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered: " + email);
        }
        if (request.getRole() == Role.DEVELOPER && userRepository.countByRole(Role.DEVELOPER) >= 1) {
            throw new IllegalArgumentException("Only one System Developer account is permitted in the system.");
        }
        if (request.getEmployeeId() != null && !request.getEmployeeId().isBlank()
                && userRepository.existsByEmployeeId(request.getEmployeeId().trim())) {
            throw new IllegalArgumentException("Employee ID already exists: " + request.getEmployeeId());
        }

        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Department not found with id: " + request.getDepartmentId()));
        }

        String employeeId = request.getEmployeeId();
        if (employeeId == null || employeeId.isBlank()) {
            employeeId = generateEmployeeId(request.getRole());
        }

        // Cryptographically secure, single-use activation token — only the hash is stored.
        String setupToken = SecureTokens.generate();
        String setupTokenHash = SecureTokens.sha256(setupToken);
        LocalDateTime tokenIssuedAt = LocalDateTime.now();
        LocalDateTime tokenExpiry = tokenIssuedAt.plusHours(SETUP_TOKEN_EXPIRY_HOURS);

        // Random placeholder password: the account cannot log in until the real
        // password is chosen through the emailed activation link.
        String placeholderPassword = passwordEncoder.encode("NEEDS_SETUP_" + UUID.randomUUID());

        User user = User.builder()
                .employeeId(employeeId)
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .email(email)
                .passwordHash(placeholderPassword)
                .role(request.getRole())
                .department(department)
                .phone(request.getPhone())
                .isActive(false)              // pending activation until password setup succeeds
                .firstLogin(true)                    .passwordSetupToken(setupTokenHash)
                    .passwordSetupTokenExpiry(tokenExpiry)
                    .passwordSetupTokenPurpose(TokenPurpose.ACTIVATION)
                    .passwordSetupTokenCreatedAt(tokenIssuedAt)
                .build();

        if (request.getLeaveApproverId() != null) {
            User primaryApprover = userRepository.findById(request.getLeaveApproverId())
                    .orElseThrow(() -> new IllegalArgumentException("Approver not found with id: " + request.getLeaveApproverId()));
            user.setLeaveApprover(primaryApprover);
        }
        if (request.getSecondaryApproverId() != null) {
            User secondaryApprover = userRepository.findById(request.getSecondaryApproverId())
                    .orElseThrow(() -> new IllegalArgumentException("Approver not found with id: " + request.getSecondaryApproverId()));
            user.setSecondaryApprover(secondaryApprover);
        }

        if (request.getRole() == Role.FACULTY && (photo == null || photo.isEmpty())) {
            throw new IllegalArgumentException("Profile photo is required. Face capture is mandatory for new Faculty accounts.");
        }

        User savedUser = userRepository.save(user);
        log.info("Account saved in DB: {} ({}) — pending activation", savedUser.getFullName(), savedUser.getEmail());

        if (photo != null && !photo.isEmpty()) {
            try {
                if (request.getRole() == Role.FACULTY) {
                    // Strictly validate the image file
                    ImageFileValidator.validatedExtension(photo);
                    // Check face detection with face microservice
                    FaceVerificationService.FaceDetectionResult detection = faceVerificationService.detectFace(photo.getBytes());
                    if (!detection.valid()) {
                        throw new IllegalArgumentException(detection.message());
                    }
                }
                String photoUrl = saveProfilePhoto(photo, savedUser.getId());
                savedUser.setProfilePictureUrl(photoUrl);
                savedUser = userRepository.save(savedUser);
                log.info("Profile photo saved for {}: {}", savedUser.getFullName(), photoUrl);
                auditService.record("FACE_REGISTERED", "USER", savedUser.getId(), savedUser.getEmail(),
                        "Face photo registered for " + savedUser.getRole());
            } catch (IllegalArgumentException e) {
                if (request.getRole() == Role.FACULTY) {
                    throw e;
                }
                log.error("Failed to save profile photo for {}: {}", savedUser.getEmail(), e.getMessage());
            } catch (Exception e) {
                if (request.getRole() == Role.FACULTY) {
                    throw new RuntimeException("Failed to process face photo: " + e.getMessage(), e);
                }
                log.error("Failed to save profile photo for {}: {}", savedUser.getEmail(), e.getMessage());
            }
        }

        final User finalSavedUser = savedUser;
        final AccountCreateResult result = AccountCreateResult.builder()
                .user(finalSavedUser)
                .emailSent(false)
                .build();

        // Side effects that must observe a committed row run after commit.
        // AuditService.record() runs in its own transaction, so the ACCOUNT_CREATED
        // entry and the later email-outcome entries are always persisted.
        auditService.record("ACCOUNT_CREATED", "USER", finalSavedUser.getId(), finalSavedUser.getEmail(),
                "Account created with role " + finalSavedUser.getRole() + " (status " + STATUS_PENDING + ")");

        // Faculty → approver mapping chosen in the create form
        if (request.getMappedFacultyIds() != null && !request.getMappedFacultyIds().isEmpty()) {
            int mapped = 0;
            for (Long facultyId : request.getMappedFacultyIds()) {
                User faculty = userRepository.findById(facultyId)
                        .orElseThrow(() -> new IllegalArgumentException("Faculty not found with id: " + facultyId));
                if (faculty.getRole() != Role.FACULTY) {
                    throw new IllegalArgumentException("Only FACULTY accounts can be mapped under an approver");
                }
                faculty.setLeaveApprover(finalSavedUser);
                userRepository.save(faculty);
                mapped++;
            }
            auditService.record("APPROVER_MAPPING", "USER", finalSavedUser.getId(), finalSavedUser.getEmail(),
                    mapped + " faculty member(s) mapped under " + finalSavedUser.getEmail());
        }

        result.setMessage("Account created.");

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean sent = emailService.sendSetupPasswordEmail(finalSavedUser, setupToken);
                result.setEmailSent(sent);
                result.setMessage(sent
                        ? "Account created. Activation email sent to " + finalSavedUser.getEmail()
                        : "Account created, but the activation email could NOT be sent to "
                            + finalSavedUser.getEmail()
                            + ". Configure SMTP, then use \"Resend activation\".");
                if (sent) {
                    log.info("Setup password email sent to {}", finalSavedUser.getEmail());
                } else {
                    log.error("Setup password email FAILED for {}", finalSavedUser.getEmail());
                }
                auditService.record(sent ? "ACTIVATION_EMAIL_SENT" : "ACTIVATION_EMAIL_FAILED",
                        "USER", finalSavedUser.getId(), finalSavedUser.getEmail(),
                        sent ? "First-time activation email delivered"
                             : "First-time activation email could NOT be delivered (mail unavailable)");
            }
        });

        // afterCommit (which sets result.emailSent) runs when this transaction commits,
        // i.e. before the proxy returns to the controller — so the controller reads
        // the real delivery outcome from the result.
        return result;
    }

    /**
     * Update an existing account (privileged fields are guarded).
     */
    @Transactional
    public UserProfileDTO updateAccount(Long accountId, AccountUpdateRequest request) {
        User actor = requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        boolean self = user.getId().equals(actor.getId());
        if (self && request.getRole() != null && request.getRole() != user.getRole()) {
            throw new IllegalStateException("You cannot change your own role");
        }
        if (self && Boolean.FALSE.equals(request.getIsActive())) {
            throw new IllegalStateException("You cannot deactivate your own account");
        }
        if (request.getRole() == Role.DEVELOPER && user.getRole() != Role.DEVELOPER) {
            if (userRepository.countByRole(Role.DEVELOPER) >= 1) {
                throw new IllegalArgumentException("Only one System Developer account is permitted in the system.");
            }
        }
        assertNotLastDeveloper(user, request.getRole(), request.getIsActive());

        List<String> changes = new ArrayList<>();
        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            user.setFirstName(request.getFirstName().trim());
            changes.add("firstName");
        }
        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            user.setLastName(request.getLastName().trim());
            changes.add("lastName");
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
            changes.add("phone");
        }
        if (request.getRole() != null && request.getRole() != user.getRole()) {
            changes.add("role " + user.getRole() + "->" + request.getRole());
            user.setRole(request.getRole());
        }
        if (request.getIsActive() != null && !request.getIsActive().equals(user.getIsActive())) {
            changes.add("active " + user.getIsActive() + "->" + request.getIsActive());
            user.setIsActive(request.getIsActive());
            if (!request.getIsActive()) {
                // DISABLING revokes any outstanding activation/reset link, so a
                // disabled account can never be activated through an old token.
                if (user.getPasswordSetupToken() != null) {
                    changes.add("activation link revoked");
                }
                user.setPasswordSetupToken(null);
                user.setPasswordSetupTokenExpiry(null);
                user.setPasswordSetupTokenPurpose(null);
            }
        }
        if (request.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Department not found"));
            user.setDepartment(dept);
            changes.add("department=" + dept.getName());
        }
        if (request.getLeaveApproverId() != null) {
            User primaryApprover = userRepository.findById(request.getLeaveApproverId())
                    .orElseThrow(() -> new IllegalArgumentException("Approver not found with id: " + request.getLeaveApproverId()));
            user.setLeaveApprover(primaryApprover);
            changes.add("leaveApprover=" + primaryApprover.getEmail());
        }
        if (request.getSecondaryApproverId() != null) {
            User secondaryApprover = userRepository.findById(request.getSecondaryApproverId())
                    .orElseThrow(() -> new IllegalArgumentException("Approver not found with id: " + request.getSecondaryApproverId()));
            user.setSecondaryApprover(secondaryApprover);
            changes.add("secondaryApprover=" + secondaryApprover.getEmail());
        }

        User saved = userRepository.save(user);
        auditService.record("ACCOUNT_UPDATED", "USER", saved.getId(), saved.getEmail(),
                changes.isEmpty() ? "No field changes" : String.join(", ", changes));
        log.info("Account updated: {} ({})", saved.getFullName(), saved.getEmail());
        return mapToProfileDTO(saved);
    }

    /**
     * Upload or update profile photo for an account (privileged Developer operation).
     * Validates image format/magic bytes/size, processes face detection for faculty,
     * persists file to disk, updates the user record, and records an audit log.
     * Preserves previous photo if validation or storage fails.
     */
    @Transactional
    public UserProfileDTO updateAccountPhoto(Long accountId, MultipartFile photo) {
        User actor = requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        // Captured BEFORE the URL is repointed so the superseded file can be
        // pruned after commit (FILE-04). The old photo stays on disk if this
        // update fails validation/storage.
        final String previousPhotoUrl = user.getProfilePictureUrl();

        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("Profile photo is required.");
        }

        try {
            // Strictly validate extension, MIME type, magic bytes, and size <= 5MB
            ImageFileValidator.validatedExtension(photo);

            // Optional face check if role is faculty
            if (user.getRole() == Role.FACULTY) {
                FaceVerificationService.FaceDetectionResult detection = faceVerificationService.detectFace(photo.getBytes());
                if (!detection.valid()) {
                    throw new IllegalArgumentException(detection.message());
                }
            }

            String photoUrl = saveProfilePhoto(photo, user.getId());
            user.setProfilePictureUrl(photoUrl);
            user = userRepository.save(user);

            log.info("Profile photo updated for {} ({}) by {}: {}", user.getFullName(), user.getEmail(), actor.getEmail(), photoUrl);
            auditService.record("ACCOUNT_UPDATED", "USER", user.getId(), user.getEmail(),
                    "Profile photo updated by " + actor.getEmail());

            // Prune the superseded file only AFTER the transaction commits, so
            // a rollback can never leave the DB row pointing at a deleted file.
            scheduleSupersededPhotoPrune(previousPhotoUrl, user.getId());

            return mapToProfileDTO(user);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update profile photo for {}: {}", user.getEmail(), e.getMessage(), e);
            String detail = (e.getMessage() != null && !e.getMessage().contains("/")) ? e.getMessage() : "File storage write error";
            throw new RuntimeException("Failed to update profile photo: " + detail, e);
        }
    }

    /**
     * Deactivate an account (safe replacement for destructive deletion).
     * Attendance and leave history are preserved; the account can be reactivated.
     */
    @Transactional
    public UserProfileDTO deactivateAccount(Long accountId) {
        User actor = requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        if (user.getId().equals(actor.getId())) {
            throw new IllegalStateException("You cannot deactivate your own account");
        }
        assertNotLastDeveloper(user, null, false);

        user.setIsActive(false);
        // Revoke any outstanding activation link.
        user.setPasswordSetupToken(null);
        user.setPasswordSetupTokenExpiry(null);
        user.setPasswordSetupTokenPurpose(null);
        User saved = userRepository.save(user);

        auditService.record("ACCOUNT_DEACTIVATED", "USER", saved.getId(), saved.getEmail(),
                "Account deactivated; attendance and leave history preserved");
        log.info("Account deactivated: {} ({}) by {}", saved.getFullName(), saved.getEmail(), actor.getEmail());
        return mapToProfileDTO(saved);
    }

    /**
     * Legacy alias kept for the existing DELETE contract — never destroys data.
     */
    @Transactional
    public void deleteAccount(Long accountId, String requestedByEmail) {
        deactivateAccount(accountId);
    }

    /**
     * Reactivate a previously deactivated account.
     */
    @Transactional
    public UserProfileDTO activateAccount(Long accountId) {
        requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        if (user.getPasswordSetupToken() != null) {
            throw new IllegalStateException(
                    "This account has not completed password setup. Resend the activation email instead.");
        }
        if (Boolean.TRUE.equals(user.getIsActive())) {
            return mapToProfileDTO(user);
        }

        user.setIsActive(true);
        user.setActivatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        auditService.record("ACCOUNT_ACTIVATED", "USER", saved.getId(), saved.getEmail(), "Account reactivated");
        log.info("Account activated: {} ({})", saved.getFullName(), saved.getEmail());
        return mapToProfileDTO(saved);
    }

    /**
     * Unlock an account by clearing any brute-force login failure lockouts.
     */
    @Transactional
    public UserProfileDTO unlockAccount(Long accountId) {
        User actor = requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        authService.clearLoginFailures(user.getEmail());

        auditService.record("ACCOUNT_UNLOCKED", "USER", user.getId(), user.getEmail(),
                "Account unlocked by " + actor.getEmail());
        log.info("Account unlocked: {} ({}) by {}", user.getFullName(), user.getEmail(), actor.getEmail());
        return mapToProfileDTO(user);
    }

    /**
     * (Re)issue an activation link for a pending account and email it.
     * Returns the real delivery outcome — no fake success messages.
     */
    @Transactional
    public Map<String, Object> resendActivation(Long accountId) {
        requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        if (user.getPasswordSetupToken() == null && Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalStateException("Account is already active. Use \"Reset password\" instead.");
        }
        assertEmailSendRateLimit(user);

        String setupToken = SecureTokens.generate();
        LocalDateTime issuedAt = LocalDateTime.now();
        // Issuing a new link ALWAYS revokes the previous one — there is never
        // more than one simultaneously valid activation link per account.
        user.setPasswordSetupToken(SecureTokens.sha256(setupToken));
        user.setPasswordSetupTokenExpiry(issuedAt.plusHours(SETUP_TOKEN_EXPIRY_HOURS));
        user.setPasswordSetupTokenPurpose(TokenPurpose.ACTIVATION);
        user.setPasswordSetupTokenCreatedAt(issuedAt);
        user.setIsActive(false);
        User saved = userRepository.save(user);

        auditService.record("ACTIVATION_RESENT", "USER", saved.getId(), saved.getEmail(),
                "New activation link issued (expires in " + SETUP_TOKEN_EXPIRY_HOURS + "h)");

        final User finalSavedUser = saved;
        Map<String, Object> response = new HashMap<>();
        response.put("emailSent", false);
        response.put("email", finalSavedUser.getEmail());
        response.put("message", "Sending activation email...");

        // afterCommit runs before this method returns to the caller, so the
        // response reflects the real SMTP outcome (no fake success).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean sent = emailService.sendSetupPasswordEmail(finalSavedUser, setupToken);
                response.put("emailSent", sent);
                response.put("message", sent
                        ? "Activation email sent to " + finalSavedUser.getEmail()
                        : "The activation email could NOT be sent to " + finalSavedUser.getEmail()
                            + ". Check the SMTP configuration and try again.");
                auditService.record(sent ? "ACTIVATION_EMAIL_SENT" : "ACTIVATION_EMAIL_FAILED",
                        "USER", finalSavedUser.getId(), finalSavedUser.getEmail(),
                        sent ? "Activation email re-sent (previous link revoked)"
                             : "Activation email could NOT be delivered (mail unavailable)");
            }
        });
        return response;
    }

    /**
     * Initiate a secure password reset: issues a fresh single-use link and emails it.
     */
    @Transactional
    public Map<String, Object> initiatePasswordReset(Long accountId) {
        requireDeveloper();
        User user = userRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with id: " + accountId));

        // Password reset is ONLY for accounts that already completed first-time
        // activation (§13 — activation and reset are separate workflows).
        if (Boolean.TRUE.equals(user.getFirstLogin()) || user.getPasswordSetupTokenPurpose() == TokenPurpose.ACTIVATION) {
            throw new IllegalStateException(
                    "This account has not completed activation yet. Use \"Resend activation\" instead.");
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalStateException(
                    "This account is disabled. Re-enable it before issuing a password reset link.");
        }
        assertEmailSendRateLimit(user);

        String setupToken = SecureTokens.generate();
        LocalDateTime issuedAt = LocalDateTime.now();
        user.setPasswordSetupToken(SecureTokens.sha256(setupToken));
        user.setPasswordSetupTokenExpiry(issuedAt.plusHours(SETUP_TOKEN_EXPIRY_HOURS));
        user.setPasswordSetupTokenPurpose(TokenPurpose.PASSWORD_RESET);
        user.setPasswordSetupTokenCreatedAt(issuedAt);
        User saved = userRepository.save(user);

        auditService.record("PASSWORD_RESET_INITIATED", "USER", saved.getId(), saved.getEmail(),
                "Password reset link issued (expires in " + SETUP_TOKEN_EXPIRY_HOURS + "h)");

        final User finalSavedUser = saved;
        Map<String, Object> response = new HashMap<>();
        response.put("emailSent", false);
        response.put("email", finalSavedUser.getEmail());
        response.put("message", "Sending password reset email...");

        // afterCommit runs before this method returns to the caller, so the
        // response reflects the real SMTP outcome (no fake success).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean sent = emailService.sendSetupPasswordEmail(finalSavedUser, setupToken);
                response.put("emailSent", sent);
                response.put("message", sent
                        ? "Password reset link sent to " + finalSavedUser.getEmail()
                        : "The password reset email could NOT be sent to " + finalSavedUser.getEmail()
                            + ". Check the SMTP configuration and try again.");
                auditService.record(sent ? "PASSWORD_RESET_EMAIL_SENT" : "PASSWORD_RESET_EMAIL_FAILED",
                        "USER", finalSavedUser.getId(), finalSavedUser.getEmail(),
                        sent ? "Password reset email delivered (previous link revoked)"
                             : "Password reset email could NOT be delivered (mail unavailable)");
            }
        });
        return response;
    }

    /**
     * Update leave approver assignments for a user.
     */
    @Transactional
    public void updateApprover(Long userId, ApproverUpdateRequest request) {
        requireDeveloper();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));

        if (request.getLeaveApproverId() != null) {
            User primaryApprover = userRepository.findById(request.getLeaveApproverId())
                    .orElseThrow(() -> new IllegalArgumentException("Approver not found with id: " + request.getLeaveApproverId()));
            user.setLeaveApprover(primaryApprover);
        } else {
            user.setLeaveApprover(null);
        }

        if (request.getSecondaryApproverId() != null) {
            User secondaryApprover = userRepository.findById(request.getSecondaryApproverId())
                    .orElseThrow(() -> new IllegalArgumentException("Approver not found with id: " + request.getSecondaryApproverId()));
            user.setSecondaryApprover(secondaryApprover);
        } else {
            user.setSecondaryApprover(null);
        }

        userRepository.save(user);
        auditService.record("APPROVER_UPDATED", "USER", user.getId(), user.getEmail(),
                "Leave approvers updated");
        log.info("Approver updated for user {}", user.getFullName());
    }

    // =========================================================
    // DEPARTMENTS
    // =========================================================

    /** Active departments (existing contract used by the create-account form). */
    @Transactional(readOnly = true)
    public List<DepartmentDTO> getDepartments() {
        requireDeveloper();
        Map<Long, Long> facultyCounts = toMap(
                userRepository.countUsersGroupByDepartmentAndRole(Role.FACULTY));
        Map<Long, Long> totalCounts = toMap(userRepository.countUsersGroupByDepartment());
        return departmentRepository.findByIsActiveTrue().stream()
                .map(dept -> toDepartmentDTO(dept, facultyCounts, totalCounts))
                .toList();
    }

    /** Full department list for the management screen (active + inactive). */
    @Transactional(readOnly = true)
    public List<DepartmentDTO> getAllDepartments() {
        requireDeveloper();
        Map<Long, Long> facultyCounts = toMap(
                userRepository.countUsersGroupByDepartmentAndRole(Role.FACULTY));
        Map<Long, Long> totalCounts = toMap(userRepository.countUsersGroupByDepartment());
        return departmentRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                .map(dept -> toDepartmentDTO(dept, facultyCounts, totalCounts))
                .toList();
    }

    @Transactional
    public DepartmentDTO createDepartment(DepartmentRequest request) {
        requireDeveloper();
        String name = request.getName().trim();
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);

        if (name.length() < 3) {
            throw new IllegalArgumentException("Department name must be at least 3 characters");
        }
        if (departmentRepository.existsByName(name)) {
            throw new IllegalArgumentException("A department named \"" + name + "\" already exists");
        }
        if (departmentRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Department code \"" + code + "\" is already in use");
        }

        Department department = Department.builder()
                .name(name)
                .code(code)
                .description(request.getDescription())
                .isActive(request.getIsActive() == null || request.getIsActive())
                .build();
        Department saved = departmentRepository.save(department);

        auditService.record("DEPARTMENT_CREATED", "DEPARTMENT", saved.getId(), saved.getCode(),
                "Department created: " + saved.getName() + " (" + saved.getCode() + ")");
        log.info("Department created: {} ({})", saved.getName(), saved.getCode());

        Map<Long, Long> facultyCounts = toMap(
                userRepository.countUsersGroupByDepartmentAndRole(Role.FACULTY));
        Map<Long, Long> totalCounts = toMap(userRepository.countUsersGroupByDepartment());
        return toDepartmentDTO(saved, facultyCounts, totalCounts);
    }

    @Transactional
    public DepartmentDTO updateDepartment(Long departmentId, DepartmentRequest request) {
        requireDeveloper();
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Department not found with id: " + departmentId));

        String name = request.getName() != null ? request.getName().trim() : department.getName();
        String code = request.getCode() != null ? request.getCode().trim().toUpperCase(Locale.ROOT) : department.getCode();

        if (name.length() < 3) {
            throw new IllegalArgumentException("Department name must be at least 3 characters");
        }
        departmentRepository.findByName(name)
                .filter(existing -> !existing.getId().equals(departmentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("A department named \"" + name + "\" already exists");
                });
        departmentRepository.findByCode(code)
                .filter(existing -> !existing.getId().equals(departmentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Department code \"" + code + "\" is already in use");
                });

        department.setName(name);
        department.setCode(code);
        if (request.getDescription() != null) {
            department.setDescription(request.getDescription());
        }
        if (request.getIsActive() != null) {
            department.setIsActive(request.getIsActive());
        }

        Department saved = departmentRepository.save(department);
        auditService.record("DEPARTMENT_UPDATED", "DEPARTMENT", saved.getId(), saved.getCode(),
                "Department updated: " + saved.getName() + " (" + saved.getCode() + "), active=" + saved.getIsActive());

        Map<Long, Long> facultyCounts = toMap(
                userRepository.countUsersGroupByDepartmentAndRole(Role.FACULTY));
        Map<Long, Long> totalCounts = toMap(userRepository.countUsersGroupByDepartment());
        return toDepartmentDTO(saved, facultyCounts, totalCounts);
    }

    /**
     * Delete a department only when it is not referenced by users or history;
     * otherwise deactivate it instead so no data is lost.
     */
    @Transactional
    public Map<String, Object> deleteDepartment(Long departmentId) {
        requireDeveloper();
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Department not found with id: " + departmentId));

        long linkedUsers = userRepository.countByDepartmentId(departmentId);
        Map<String, Object> response = new HashMap<>();

        if (linkedUsers > 0) {
            department.setIsActive(false);
            departmentRepository.save(department);
            auditService.record("DEPARTMENT_DEACTIVATED", "DEPARTMENT", department.getId(), department.getCode(),
                    "Deactivated instead of delete: " + linkedUsers + " linked user(s)");
            response.put("deleted", false);
            response.put("message", "Department has " + linkedUsers
                    + " linked user(s), so it was deactivated instead of deleted. No data was removed.");
            return response;
        }

        departmentRepository.delete(department);
        auditService.record("DEPARTMENT_DELETED", "DEPARTMENT", departmentId, department.getCode(),
                "Department deleted: " + department.getName());
        response.put("deleted", true);
        response.put("message", "Department \"" + department.getName() + "\" deleted.");
        return response;
    }

    // =========================================================
    // APPROVERS + STATS
    // =========================================================

    /** Get all users who can be leave approvers (HOD, TPO, PRINCIPAL, VC) */
    @Transactional(readOnly = true)
    public List<UserProfileDTO> getApprovers() {
        requireDeveloper();
        return userRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> List.of(Role.HOD, Role.TPO, Role.PRINCIPAL, Role.VC).contains(u.getRole()))
                .map(this::mapToProfileDTO)
                .toList();
    }

    /**
     * Developer dashboard statistics — every number is computed by the database.
     * Uses grouped/count queries only (no N+1 scans).
     */
    @Transactional(readOnly = true)
    public DeveloperStatsDTO getStats() {
        requireDeveloper();

        long total = userRepository.count();
        long faculty = userRepository.countByRole(Role.FACULTY);
        long admins = total - faculty;
        long departments = departmentRepository.count();
        long active = userRepository.countActiveAccounts();
        long inactive = userRepository.countInactiveAccounts();
        long pending = userRepository.countPendingActivation();

        Map<String, Long> roleCounts = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            roleCounts.put(role.name(), 0L);
        }
        for (Object[] row : userRepository.countGroupByRole()) {
            roleCounts.put(((Role) row[0]).name(), (Long) row[1]);
        }

        Map<Long, Long> totalByDept = toMap(userRepository.countUsersGroupByDepartment());
        Map<Long, Long> facultyByDept = toMap(userRepository.countUsersGroupByDepartmentAndRole(Role.FACULTY));
        Map<Long, Long> activeByDept = toMap(userRepository.countActiveUsersGroupByDepartment());
        List<DeveloperStatsDTO.DepartmentStat> departmentWise =
                departmentRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                        .map(d -> DeveloperStatsDTO.DepartmentStat.builder()
                                .departmentId(d.getId())
                                .name(d.getName())
                                .code(d.getCode())
                                .isActive(Boolean.TRUE.equals(d.getIsActive()))
                                .totalUsers(totalByDept.getOrDefault(d.getId(), 0L))
                                .facultyCount(facultyByDept.getOrDefault(d.getId(), 0L))
                                .activeUsers(activeByDept.getOrDefault(d.getId(), 0L))
                                .build())
                        .toList();

        List<DeveloperStatsDTO.RecentAccount> recentAccounts =
                userRepository.findTop5ByOrderByCreatedAtDesc().stream()
                        .map(u -> DeveloperStatsDTO.RecentAccount.builder()
                                .id(u.getId())
                                .fullName(u.getFullName())
                                .email(u.getEmail())
                                .role(u.getRole().name())
                                .departmentName(u.getDepartment() != null ? u.getDepartment().getName() : null)
                                .activationStatus(activationStatus(u))
                                .createdAt(u.getCreatedAt())
                                .build())
                        .toList();

        List<DeveloperStatsDTO.AuditEntry> recentActions =
                auditLogRepository.findTop10ByOrderByCreatedAtDesc().stream()
                        .map(a -> DeveloperStatsDTO.AuditEntry.builder()
                                .id(a.getId())
                                .action(a.getAction())
                                .actorEmail(a.getActorEmail())
                                .targetType(a.getTargetType())
                                .targetEmail(a.getTargetEmail())
                                .details(a.getDetails())
                                .createdAt(a.getCreatedAt())
                                .build())
                        .toList();

        LocalDate today = LocalDate.now();
        DeveloperStatsDTO.AttendanceStatus attendance = DeveloperStatsDTO.AttendanceStatus.builder()
                .date(today)
                .activeFaculty(userRepository.countByRoleAndIsActiveTrue(Role.FACULTY))
                .marked(attendanceLogRepository.countByAttendanceDate(today))
                .present(attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.PRESENT))
                .late(attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.LATE))
                .absent(attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.ABSENT))
                .onLeave(attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.ON_LEAVE))
                .halfDay(attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.HALF_DAY))
                .build();

        return DeveloperStatsDTO.builder()
                .total(total)
                .faculty(faculty)
                .admins(admins)
                .departments(departments)
                .active(active)
                .inactive(inactive)
                .pendingActivations(pending)
                .roleCounts(roleCounts)
                .departmentWise(departmentWise)
                .recentAccounts(recentAccounts)
                .recentActions(recentActions)
                .attendance(attendance)
                .build();
    }

    /** Recent administrative actions (audit trail). */
    @Transactional(readOnly = true)
    public List<AuditLog> getRecentAuditLogs(int limit) {
        requireDeveloper();
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, safeLimit,
                                Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
    }

    // =========================================================
    // HELPERS
    // =========================================================

    /** ACTIVE | INACTIVE | PENDING activation status of an account. */
    public String activationStatus(User user) {
        if (user.getPasswordSetupToken() != null) {
            return STATUS_PENDING;
        }
        return Boolean.TRUE.equals(user.getIsActive()) ? STATUS_ACTIVE : STATUS_INACTIVE;
    }

    /**
     * In-memory throttle for email-sending actions (activation resend / password
     * reset) so an administrator action cannot be turned into an email bomb.
     */
    private void assertEmailSendRateLimit(User user) {
        LocalDateTime now = LocalDateTime.now();
        Deque<LocalDateTime> window = emailSendWindows.computeIfAbsent(user.getId(), k -> new ArrayDeque<>());
        synchronized (window) {
            LocalDateTime cutoff = now.minusMinutes(Math.max(1, resendWindowMinutes));
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= Math.max(1, resendMaxAttempts)) {
                throw new RateLimitException(
                        "Too many emails requested for this account. Please wait "
                                + resendWindowMinutes + " minutes and try again.");
            }
            window.addLast(now);
        }
    }

    /**
     * Never allow the system to end up without a usable Developer account,
     * and never allow demoting/disabling yourself indirectly.
     */
    private void assertNotLastDeveloper(User target, Role requestedRole, Boolean requestedActive) {
        boolean demoting = requestedRole != null && requestedRole != Role.DEVELOPER && target.getRole() == Role.DEVELOPER;
        boolean disabling = Boolean.FALSE.equals(requestedActive) && target.getRole() == Role.DEVELOPER;
        if (!demoting && !disabling) {
            return;
        }
        long activeDevelopers = userRepository.countByRoleAndIsActiveTrue(Role.DEVELOPER);
        if (target.getRole() == Role.DEVELOPER && Boolean.TRUE.equals(target.getIsActive())) {
            activeDevelopers -= 1;
        }
        if (activeDevelopers <= 0) {
            throw new IllegalStateException(
                    "Cannot modify or disable the last active Developer account — the system would become unmanageable");
        }
    }

    private Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((Long) row[0], row[1] == null ? 0L : (Long) row[1]);
            }
        }
        return map;
    }

    private DepartmentDTO toDepartmentDTO(Department dept, Map<Long, Long> facultyCounts, Map<Long, Long> totalCounts) {
        return DepartmentDTO.builder()
                .id(dept.getId())
                .name(dept.getName())
                .code(dept.getCode())
                .description(dept.getDescription())
                .isActive(dept.getIsActive())
                .facultyCount(facultyCounts.getOrDefault(dept.getId(), 0L))
                .totalUsers(totalCounts.getOrDefault(dept.getId(), 0L))
                .createdAt(dept.getCreatedAt())
                .build();
    }

    /**
     * Best-effort prune of a superseded profile photo AFTER the enclosing
     * transaction commits (never inside it). A prune failure is logged and
     * swallowed: the scheduled PhotoCleanupService sweep will reclaim the
     * file once it is past the retention window.
     */
    private void scheduleSupersededPhotoPrune(String previousPhotoUrl, Long userId) {
        if (previousPhotoUrl == null || previousPhotoUrl.isBlank()) {
            return;
        }
        Runnable prune = () -> {
            try {
                photoCleanupService.pruneSupersededPhoto(previousPhotoUrl, userId);
            } catch (Exception e) {
                log.warn("Superseded profile photo {} left in place (scheduled sweep will reclaim): {}",
                        previousPhotoUrl, e.getMessage());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            prune.run();
                        }
                    });
        } else {
            prune.run();
        }
    }

    /**
     * Generate a unique employee ID based on role.
     */
    private String generateEmployeeId(Role role) {
        String prefix = switch (role) {
            case FACULTY -> "FAC";
            case HOD -> "HOD";
            case PRINCIPAL -> "PRN";
            case VC -> "VC";
            case TPO -> "TPO";
            case DEVELOPER -> "DEV";
        };

        long count = userRepository.countByRole(role) + 1;
        String candidate;
        do {
            candidate = String.format("%s%03d", prefix, count);
            count++;
        } while (userRepository.existsByEmployeeId(candidate));
        return candidate;
    }

    /**
     * Save profile photo to uploads directory.
     * Validation (extension whitelist, MIME type, real image magic bytes,
     * 5 MB limit) is shared with the profile self-service upload via
     * ImageFileValidator; the filename is server-generated.
     * @return the URL path to the saved photo
     */
    private String saveProfilePhoto(MultipartFile photo, Long userId) throws IOException {
        String extension = ImageFileValidator.validatedExtension(photo);
        // Full-length UUID suffix: 128 bits of randomness so a random collision
        // can never silently overwrite a previously stored photo (the storage
        // job uses REPLACE_EXISTING). Keeps the user_<id>_ prefix for
        // traceability and test compatibility.
        String filename = "user_" + userId + "_" + UUID.randomUUID().toString() + extension;
        return fileStorageService.storeProfilePicture(photo, filename);
    }

    /**
     * Map User entity to UserProfileDTO (never exposes tokens or password hash)
     */
    private UserProfileDTO mapToProfileDTO(User user) {
        return UserProfileDTO.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole())
                .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
                .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
                .employeeId(user.getEmployeeId())
                .phone(user.getPhone())
                .isActive(user.getIsActive())
                .profilePictureUrl(user.getProfilePictureUrl())
                .leaveApproverId(user.getLeaveApprover() != null ? user.getLeaveApprover().getId() : null)
                .leaveApproverName(user.getLeaveApprover() != null ? user.getLeaveApprover().getFullName() : null)
                .secondaryApproverId(user.getSecondaryApprover() != null ? user.getSecondaryApprover().getId() : null)
                .secondaryApproverName(user.getSecondaryApprover() != null ? user.getSecondaryApprover().getFullName() : null)
                .activationStatus(activationStatus(user))
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .build();
    }
}
