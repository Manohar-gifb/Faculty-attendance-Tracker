package com.college.attendance.controller;

import com.college.attendance.dto.*;
import com.college.attendance.entity.AuditLog;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.service.DeveloperService;
import com.college.attendance.service.PhotoCleanupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Developer / Super Admin module.
 *
 * Every endpoint is protected three times:
 *  1. SecurityConfig  -> /developer/** requires ROLE_DEVELOPER
 *  2. @PreAuthorize    -> method-level check on this whole controller
 *  3. DeveloperService -> service-level requireDeveloper() guard
 */
@RestController
@RequestMapping("/developer")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('DEVELOPER')")
public class DeveloperController {

    private final DeveloperService developerService;
    private final PhotoCleanupService photoCleanupService;

    /**
     * Dry-run lifecycle report for stored profile photos (FILE-04).
     * Pure read-only scan: referenced/protected files, unrecognized names,
     * orphan candidates within and beyond the retention grace period.
     */
    @GetMapping("/photo-cleanup/report")
    public ResponseEntity<Map<String, Object>> photoCleanupReport() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("deletionEnabled", photoCleanupService.isDeletionEnabled());
        body.put("retentionHours", photoCleanupService.getRetentionHours());
        body.put("report", photoCleanupService.report());
        return ResponseEntity.ok(body);
    }

    /**
     * Execute the lifecycle sweep now (FILE-04). This is DRY-RUN (a scan with
     * no deletions) unless app.photo-cleanup.deletion-enabled=true is configured
     * explicitly — the operator opts into deletion via configuration, never
     * via this API, so an accidental POST cannot destroy files.
     */
    @PostMapping("/photo-cleanup/run")
    public ResponseEntity<Map<String, Object>> runPhotoCleanup() {
        var report = photoCleanupService.runCleanup(true);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("deletionEnabled", photoCleanupService.isDeletionEnabled());
        body.put("dryRun", report.isDryRun());
        body.put("deleted", report.deleted());
        body.put("report", report);
        return ResponseEntity.ok(body);
    }

    /**
     * Get all accounts (legacy contract: full list, no pagination)
     */
    @GetMapping("/accounts")
    public ResponseEntity<List<UserProfileDTO>> getAllAccounts(
            @AuthenticationPrincipal UserDetails userDetails) {
        log.debug("getAllAccounts requested by: {}", userDetails.getUsername());
        return ResponseEntity.ok(developerService.getAllAccounts());
    }

    /**
     * Backend paginated / sorted / filtered account list.
     *
     * Params: q, role, departmentId, status (ACTIVE|INACTIVE|PENDING), sort, direction, page, size
     */
    @GetMapping("/accounts/search")
    public ResponseEntity<PagedAccountsDTO> searchAccounts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", required = false, defaultValue = "createdAt") String sort,
            @RequestParam(value = "direction", required = false, defaultValue = "desc") String direction,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(developerService.getAccountsPage(q, role, departmentId, status, sort, direction, page, size));
    }

    /**
     * Get all active departments (used by the create-account form)
     */
    @GetMapping("/departments")
    public ResponseEntity<List<DepartmentDTO>> getDepartments() {
        return ResponseEntity.ok(developerService.getDepartments());
    }

    /**
     * All departments (active + inactive) with faculty counts — management screen
     */
    @GetMapping("/departments/all")
    public ResponseEntity<List<DepartmentDTO>> getAllDepartments() {
        return ResponseEntity.ok(developerService.getAllDepartments());
    }

    /**
     * Create a department
     */
    @PostMapping("/departments")
    public ResponseEntity<DepartmentDTO> createDepartment(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(developerService.createDepartment(request));
    }

    /**
     * Update a department (name, code, description, active flag)
     */
    @PutMapping("/departments/{departmentId}")
    public ResponseEntity<DepartmentDTO> updateDepartment(
            @PathVariable("departmentId") Long departmentId,
            @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(developerService.updateDepartment(departmentId, request));
    }

    /**
     * Delete a department — blocked (converted to deactivation) when users are linked
     */
    @DeleteMapping("/departments/{departmentId}")
    public ResponseEntity<Map<String, Object>> deleteDepartment(@PathVariable("departmentId") Long departmentId) {
        return ResponseEntity.ok(developerService.deleteDepartment(departmentId));
    }

    /**
     * Get all potential leave approvers
     */
    @GetMapping("/approvers")
    public ResponseEntity<List<UserProfileDTO>> getApprovers() {
        return ResponseEntity.ok(developerService.getApprovers());
    }

    /**
     * Create a new account (JSON — no photo).
     * The account is created PENDING and a single-use activation link is emailed.
     * The response reports the real email delivery outcome.
     */
    @PostMapping(value = "/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createAccount(
            @Valid @RequestBody AccountCreateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("createAccount requested by: {} for email: {}", userDetails.getUsername(), request.getEmail());

        if (request.getRole() == Role.FACULTY) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Profile photo is required. Face capture is mandatory for new Faculty accounts. Please capture and attach the faculty member's face photo using multipart form-data.",
                    "status", HttpStatus.BAD_REQUEST.value()
            ));
        }

        AccountCreateResult result = developerService.createAccount(request, null);

        return ResponseEntity.ok(Map.of(
                "message", result.getMessage(),
                "email", result.getUser().getEmail(),
                "userId", result.getUser().getId(),
                "emailSent", result.isEmailSent(),
                "activationStatus", "PENDING"
        ));
    }

    /**
     * Create a new account with profile photo (multipart).
     * Face capture is mandatory for FACULTY accounts.
     */
    @PostMapping(value = "/accounts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createAccountWithPhoto(
            @RequestPart("request") @Valid AccountCreateRequest request,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("createAccount (multipart) requested by: {} for email: {} (photo={})",
                userDetails.getUsername(), request.getEmail(), photo != null);

        if (request.getRole() == Role.FACULTY && (photo == null || photo.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Profile photo is required. Face capture is mandatory for new Faculty accounts. Please capture and attach the faculty member's face photo.",
                    "status", HttpStatus.BAD_REQUEST.value()
            ));
        }

        AccountCreateResult result = developerService.createAccount(request, photo);

        return ResponseEntity.ok(Map.of(
                "message", result.getMessage(),
                "email", result.getUser().getEmail(),
                "userId", result.getUser().getId(),
                "emailSent", result.isEmailSent(),
                "activationStatus", "PENDING"
        ));
    }

    /**
     * Update an account
     */
    @PutMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> updateAccount(
            @PathVariable("accountId") Long accountId,
            @RequestBody AccountUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("updateAccount requested by: {} for accountId: {}", userDetails.getUsername(), accountId);

        UserProfileDTO updated = developerService.updateAccount(accountId, request);
        return ResponseEntity.ok(Map.of(
                "message", "Account updated successfully",
                "account", updated
        ));
    }

    /**
     * Upload or update profile photo for an account (privileged operation).
     * Saves photo persistently, re-validates image content/type/size, and links to user.
     */
    @PostMapping(value = "/accounts/{accountId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAccountPhoto(
            @PathVariable("accountId") Long accountId,
            @RequestParam("photo") MultipartFile photo,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("uploadAccountPhoto requested by: {} for accountId: {}", userDetails.getUsername(), accountId);

        if (photo == null || photo.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Profile photo is required. Please select or capture an image.",
                    "status", HttpStatus.BAD_REQUEST.value()
            ));
        }

        UserProfileDTO updated = developerService.updateAccountPhoto(accountId, photo);
        return ResponseEntity.ok(Map.of(
                "message", "Profile photo updated successfully",
                "account", updated
        ));
    }

    /**
     * Deactivate an account (safe "delete": history is preserved)
     */
    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @PathVariable("accountId") Long accountId,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("deleteAccount (deactivate) requested by: {} for accountId: {}", userDetails.getUsername(), accountId);

        developerService.deleteAccount(accountId, userDetails.getUsername());
        return ResponseEntity.ok(Map.of("message", "Account deactivated. Attendance and leave history preserved."));
    }

    /**
     * Reactivate a deactivated account
     */
    @PostMapping("/accounts/{accountId}/activate")
    public ResponseEntity<Map<String, Object>> activateAccount(@PathVariable("accountId") Long accountId) {
        UserProfileDTO account = developerService.activateAccount(accountId);
        return ResponseEntity.ok(Map.of("message", "Account activated", "account", account));
    }

    /**
     * Deactivate an account (explicit endpoint, same as DELETE)
     */
    @PostMapping("/accounts/{accountId}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateAccount(@PathVariable("accountId") Long accountId) {
        UserProfileDTO account = developerService.deactivateAccount(accountId);
        return ResponseEntity.ok(Map.of("message", "Account deactivated. History preserved.", "account", account));
    }

    /**
     * Unlock an account that is locked due to repeated failed login attempts
     */
    @PostMapping("/accounts/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlockAccount(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("unlockAccount requested by: {} for accountId: {}", userDetails.getUsername(), id);
        UserProfileDTO account = developerService.unlockAccount(id);
        return ResponseEntity.ok(Map.of("message", "Account unlocked successfully", "account", account));
    }

    /**
     * Re-issue and email the activation link; reports the real delivery outcome
     */
    @PostMapping("/accounts/{accountId}/resend-activation")
    public ResponseEntity<Map<String, Object>> resendActivation(@PathVariable("accountId") Long accountId) {
        Map<String, Object> response = developerService.resendActivation(accountId);
        if (!Boolean.TRUE.equals(response.get("emailSent"))) {
            return ResponseEntity.status(502).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Initiate a secure password reset (single-use link emailed to the user)
     */
    @PostMapping("/accounts/{accountId}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable("accountId") Long accountId) {
        Map<String, Object> response = developerService.initiatePasswordReset(accountId);
        if (!Boolean.TRUE.equals(response.get("emailSent"))) {
            return ResponseEntity.status(502).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Update leave approver for a user
     */
    @PutMapping("/accounts/{userId}/approver")
    public ResponseEntity<Map<String, String>> updateApprover(
            @PathVariable("userId") Long userId,
            @RequestBody ApproverUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.info("updateApprover requested by: {} for userId: {}", userDetails.getUsername(), userId);

        developerService.updateApprover(userId, request);
        return ResponseEntity.ok(Map.of("message", "Approver updated successfully"));
    }

    /**
     * Get developer dashboard stats (real data from MySQL)
     */
    @GetMapping("/stats")
    public ResponseEntity<DeveloperStatsDTO> getStats() {
        return ResponseEntity.ok(developerService.getStats());
    }

    /**
     * Recent administrative actions (audit trail)
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLog>> getAuditLogs(
            @RequestParam(value = "limit", required = false, defaultValue = "25") int limit) {
        return ResponseEntity.ok(developerService.getRecentAuditLogs(limit));
    }
}
