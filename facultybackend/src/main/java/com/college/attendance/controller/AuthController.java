package com.college.attendance.controller;

import com.college.attendance.dto.AuthRequest;
import com.college.attendance.dto.AuthResponse;
import com.college.attendance.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Login endpoint - returns JWT token
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Public self-service forgot-password request.
     *
     * Returns an identical generic response for known and unknown emails
     * (no account enumeration). Throttling (per-email), account-status rules
     * and single-use/HASHED token issuance live in AuthService. Delivery
     * failures are audited server-side and never faked as success.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> request) {
        String email = request.get("email");

        if (email == null || email.isBlank()
                || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Please enter a valid email address."));
        }

        Map<String, Object> response = authService.initiateSelfServicePasswordReset(email);
        return ResponseEntity.ok(response);
    }

    /**
     * Change password on first login (mandatory)
     */
    @PostMapping("/change-password-first")
    public ResponseEntity<Map<String, String>> changePasswordFirstLogin(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        String newPassword = request.get("newPassword");
        String confirmPassword = request.get("confirmPassword");

        if (newPassword == null || newPassword.isBlank()) {
            throw new RuntimeException("New password is required");
        }
        if (newPassword.length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new RuntimeException("Passwords do not match");
        }

        authService.changePassword(userDetails.getUsername(), newPassword);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * Change password (voluntary)
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        String currentPassword = request.get("currentPassword");
        String newPassword = request.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            throw new RuntimeException("Current and new password are required");
        }
        if (newPassword.length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }

        authService.changePasswordWithVerification(userDetails.getUsername(), currentPassword, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * Setup password via token (new user first-time setup from email link)
     */
    @PostMapping("/setup-password")
    public ResponseEntity<Map<String, String>> setupPassword(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        String newPassword = request.get("newPassword");
        String confirmPassword = request.get("confirmPassword");

        if (token == null || token.isBlank()) {
            throw new RuntimeException("Setup token is required");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new RuntimeException("New password is required");
        }
        if (newPassword.length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new RuntimeException("Passwords do not match");
        }

        authService.setupPassword(token, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password set successfully. You can now log in."));
    }

    /**
     * Validate setup token (check if it's valid and not expired)
     */
    @GetMapping("/setup-password/validate")
    public ResponseEntity<Map<String, Object>> validateSetupToken(@RequestParam("token") String token) {
        boolean valid = authService.isValidSetupToken(token);
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("valid", valid);

        if (!valid) {
            // "EXPIRED" (link exists but timed out) vs "INVALID" (unknown or
            // already used) — never reveals user/token/database details.
            response.put("reason", authService.getSetupTokenFailureReason(token));
        }

        if (valid) {
            var user = authService.getUserBySetupToken(token);
            response.put("fullName", user.getFullName());
            response.put("email", user.getEmail());
            response.put("employeeId", user.getEmployeeId());
            response.put("role", user.getRole());
        }

        return ResponseEntity.ok(response);
    }
}
