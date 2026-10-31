# First-Time Login & Password Change Workflow

## College Faculty Attendance Management System

## Overview
This document describes the secure developer-controlled faculty/admin onboarding flow.

---

## 1. MySQL Database Schema Updates

```sql
-- Add columns to the existing `users` table
ALTER TABLE users
  ADD COLUMN temp_password_hash VARCHAR(255) DEFAULT NULL COMMENT 'BCrypt hash of the auto-generated temporary password',
  ADD COLUMN is_first_login BOOLEAN DEFAULT TRUE COMMENT 'Forces password change on first login',
  ADD COLUMN password_changed_at TIMESTAMP NULL DEFAULT NULL COMMENT 'Timestamp of last password change',
  ADD COLUMN login_attempts INT DEFAULT 0 COMMENT 'Track failed login attempts',
  ADD COLUMN account_locked_until TIMESTAMP NULL DEFAULT NULL COMMENT 'Account lock expiry timestamp';

-- Approver mapping: who approves leave requests for this user
ALTER TABLE users
  ADD COLUMN leave_approver_id BIGINT DEFAULT NULL COMMENT 'FK to users.id - HOD/TPO who approves leave for this user',
  ADD COLUMN secondary_approver_id BIGINT DEFAULT NULL COMMENT 'FK to users.id - Secondary approver (e.g. Principal for HODs)';

-- Add foreign key constraints
ALTER TABLE users
  ADD CONSTRAINT fk_leave_approver
    FOREIGN KEY (leave_approver_id) REFERENCES users(id)
    ON DELETE SET NULL,
  ADD CONSTRAINT fk_secondary_approver
    FOREIGN KEY (secondary_approver_id) REFERENCES users(id)
    ON DELETE SET NULL;

-- Indexes
CREATE INDEX idx_users_first_login ON users(is_first_login);
CREATE INDEX idx_users_leave_approver ON users(leave_approver_id);

-- Approver approvals table (tracks who approved what)
CREATE TABLE leave_approvals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  leave_request_id BIGINT NOT NULL,
  approver_id BIGINT NOT NULL COMMENT 'FK to users.id - who approved',
  status ENUM('PENDING', 'APPROVED', 'REJECTED') DEFAULT 'PENDING',
  comments TEXT,
  approved_at TIMESTAMP NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (approver_id) REFERENCES users(id),
  FOREIGN KEY (leave_request_id) REFERENCES leave_requests(id)
);
```

---

## 2. Spring Boot Backend Code

### 2.1 Entity Model (`User.java`)

```java
package com.college.attendance.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "temp_password_hash")
    private String tempPasswordHash;

    @Column(name = "is_first_login", nullable = false)
    private boolean isFirstLogin = true;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String role; // FACULTY, HOD, PRINCIPAL, VC, TPO, DEVELOPER

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "phone")
    private String phone;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "login_attempts")
    private int loginAttempts = 0;

    @Column(name = "account_locked_until")
    private LocalDateTime accountLockedUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_approver_id")
    private User leaveApprover;  // HOD/TPO who approves leave for this user

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "secondary_approver_id")
    private User secondaryApprover;  // Secondary approver (e.g. Principal for HODs)

    @Column(name = "department_name")
    private String departmentName;

    @Column(name = "profile_picture_url")
    private String profilePictureUrl;

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getTempPasswordHash() { return tempPasswordHash; }
    public void setTempPasswordHash(String tempPasswordHash) { this.tempPasswordHash = tempPasswordHash; }

    public boolean isFirstLogin() { return isFirstLogin; }
    public void setFirstLogin(boolean firstLogin) { isFirstLogin = firstLogin; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }

    public int getLoginAttempts() { return loginAttempts; }
    public void setLoginAttempts(int loginAttempts) { this.loginAttempts = loginAttempts; }

    public LocalDateTime getAccountLockedUntil() { return accountLockedUntil; }
    public void setAccountLockedUntil(LocalDateTime accountLockedUntil) { this.accountLockedUntil = accountLockedUntil; }
}
```

### 2.2 DTOs

```java
// AuthResponse.java
package com.college.attendance.dto;

public class AuthResponse {
    private String token;
    private String fullName;
    private String email;
    private String role;
    private String departmentName;
    private Long userId;
    private String profilePictureUrl;
    private boolean firstLogin;       // <-- NEW: tells frontend to redirect
    private String message;           // <-- NEW: optional message

    // Constructors
    public AuthResponse() {}

    public AuthResponse(String token, String fullName, String email, String role,
                        String departmentName, Long userId, String profilePictureUrl,
                        boolean firstLogin, String message) {
        this.token = token;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
        this.departmentName = departmentName;
        this.userId = userId;
        this.profilePictureUrl = profilePictureUrl;
        this.firstLogin = firstLogin;
        this.message = message;
    }

    // Getters and Setters
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProfilePictureUrl() { return profilePictureUrl; }
    public void setProfilePictureUrl(String profilePictureUrl) { this.profilePictureUrl = profilePictureUrl; }
    public boolean isFirstLogin() { return firstLogin; }
    public void setFirstLogin(boolean firstLogin) { this.firstLogin = firstLogin; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}

// ChangePasswordRequest.java
package com.college.attendance.dto;

public class ChangePasswordRequest {
    private String currentPassword;
    private String newPassword;
    private String confirmPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}

// DeveloperCreateAccountRequest.java
package com.college.attendance.dto;

public class DeveloperCreateAccountRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String role;
    private Long departmentId;
    private String employeeId;
    private Long leaveApproverId;      // HOD/TPO who approves leave for this user
    private Long secondaryApproverId;  // Secondary approver (e.g. Principal for HODs)

    // Getters and Setters
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public Long getLeaveApproverId() { return leaveApproverId; }
    public void setLeaveApproverId(Long leaveApproverId) { this.leaveApproverId = leaveApproverId; }
    public Long getSecondaryApproverId() { return secondaryApproverId; }
    public void setSecondaryApproverId(Long secondaryApproverId) { this.secondaryApproverId = secondaryApproverId; }
}
```

### 2.3 AuthService (`AuthService.java`)

```java
package com.college.attendance.service;

import com.college.attendance.dto.AuthResponse;
import com.college.attendance.dto.ChangePasswordRequest;
import com.college.attendance.entity.User;
import com.college.attendance.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // =====================================================
    // GENERATE SECURE TEMPORARY PASSWORD
    // =====================================================
    public String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Example output: "aB3_kL9mNpQ2"
    }

    // =====================================================
    // LOGIN (checks is_first_login flag)
    // =====================================================
    public AuthResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        // Check if account is locked
        if (user.getAccountLockedUntil() != null
                && user.getAccountLockedUntil().isAfter(LocalDateTime.now())) {
            throw new RuntimeException("Account is temporarily locked. Try again later.");
        }

        // Check temp password first (first login), then permanent password
        boolean tempMatch = user.getTempPasswordHash() != null
                && passwordEncoder.matches(password, user.getTempPasswordHash());
        boolean permMatch = passwordEncoder.matches(password, user.getPasswordHash());

        if (!tempMatch && !permMatch) {
            user.setLoginAttempts(user.getLoginAttempts() + 1);
            if (user.getLoginAttempts() >= 5) {
                user.setAccountLockedUntil(LocalDateTime.now().plusMinutes(30));
            }
            userRepository.save(user);
            throw new RuntimeException("Invalid email or password");
        }

        // Reset login attempts on success
        user.setLoginAttempts(0);
        user.setAccountLockedUntil(null);
        userRepository.save(user);

        // Build response with firstLogin flag
        return AuthResponse.builder()
            .token(jwtTokenProvider.generateToken(user))
            .fullName(user.getFirstName() + " " + user.getLastName())
            .email(user.getEmail())
            .role(user.getRole())
            .departmentName(user.getDepartmentName())
            .userId(user.getId())
            .profilePictureUrl(user.getProfilePictureUrl())
            .firstLogin(user.isFirstLogin())       // <-- KEY: tells frontend
            .message(user.isFirstLogin()
                ? "Please change your password before continuing"
                : null)
            .build();
    }

    // =====================================================
    // FORCE CHANGE PASSWORD (first login)
    // =====================================================
    public void changePasswordFirstLogin(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isFirstLogin()) {
            throw new RuntimeException("Password already changed. Please log in normally.");
        }

        // Validate new password
        if (request.getNewPassword() == null || request.getNewPassword().length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new RuntimeException("Passwords do not match");
        }

        // Hash and save new password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setFirstLogin(false);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setTempPasswordHash(null); // Clear temp password
        userRepository.save(user);
    }

    // =====================================================
    // CHANGE PASSWORD (normal - already logged in)
    // =====================================================
    public void changePasswordNormal(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Current password is incorrect");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new RuntimeException("Passwords do not match");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);
    }
}
```

### 2.4 AuthController (`AuthController.java`)

```java
package com.college.attendance.controller;

import com.college.attendance.dto.AuthResponse;
import com.college.attendance.dto.ChangePasswordRequest;
import com.college.attendance.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    // =====================================================
    // LOGIN
    // =====================================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        try {
            AuthResponse response = authService.login(email, password);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage()));
        }
    }

    // =====================================================
    // CHANGE PASSWORD (first login - requires JWT)
    // =====================================================
    @PostMapping("/change-password-first")
    public ResponseEntity<?> changePasswordFirstLogin(
            @RequestHeader("Authorization") String token,
            @RequestBody ChangePasswordRequest request) {
        try {
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            authService.changePasswordFirstLogin(userId, request);
            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage()));
        }
    }

    // =====================================================
    // CHANGE PASSWORD (normal - requires JWT)
    // =====================================================
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestHeader("Authorization") String token,
            @RequestBody ChangePasswordRequest request) {
        try {
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            authService.changePasswordNormal(userId, request);
            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage()));
        }
    }
}
```

### 2.5 Developer Controller (Account Creation)

```java
package com.college.attendance.controller;

import com.college.attendance.dto.DeveloperCreateAccountRequest;
import com.college.attendance.entity.User;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.AuthService;
import com.college.attendance.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/developer")
public class DeveloperController {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthService authService;
    @Autowired private EmailService emailService;

    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(
            @RequestBody DeveloperCreateAccountRequest request) {

        // Check if email already exists
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.badRequest()
                .body(Map.of("message", "Email already registered"));
        }

        // Generate temporary password
        String tempPassword = authService.generateTemporaryPassword();
        String tempPasswordHash = passwordEncoder.encode(tempPassword);

        // Create user
        User user = new User();
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole(request.getRole());
        user.setDepartmentId(request.getDepartmentId());
        user.setEmployeeId(request.getEmployeeId());
        user.setTempPasswordHash(tempPasswordHash);
        user.setPasswordHash(passwordEncoder.encode(tempPassword)); // initial hash
        user.setFirstLogin(true);

        // Map leave approvers (only for FACULTY role)
        if (request.getLeaveApproverId() != null) {
            User approver = userRepository.findById(request.getLeaveApproverId())
                .orElseThrow(() -> new RuntimeException("Leave approver not found"));
            user.setLeaveApprover(approver);
        }
        if (request.getSecondaryApproverId() != null) {
            User secondary = userRepository.findById(request.getSecondaryApproverId())
                .orElseThrow(() -> new RuntimeException("Secondary approver not found"));
            user.setSecondaryApprover(secondary);
        }

        userRepository.save(user);

        // Send welcome email with temporary password
        emailService.sendWelcomeEmail(
            request.getEmail(),
            request.getFirstName(),
            tempPassword,
            request.getRole()
        );

        return ResponseEntity.ok(Map.of(
            "message", "Account created successfully",
            "email", request.getEmail()
        ));
    }
}
```

### 2.6 Email Service (`EmailService.java`)

```java
package com.college.attendance.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendWelcomeEmail(String to, String firstName,
                                  String tempPassword, String role) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setTo(to);
            helper.setSubject("Welcome to Faculty Sync Tracker - Your Login Credentials");

            String htmlContent = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <div style="background: linear-gradient(135deg, #2563eb, #4f46e5);
                                padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: white; margin: 0;">🎓 Faculty Sync Tracker</h1>
                        <p style="color: rgba(255,255,255,0.8); margin: 4px 0 0; font-size: 14px;">
                            College Faculty Attendance Management System
                        </p>
                    </div>
                    <div style="background: #f8fafc; padding: 30px; border: 1px solid #e2e8f0;">
                        <h2 style="color: #1e293b;">Welcome, %s!</h2>
                        <p style="color: #475569;">Your account has been created as <strong>%s</strong>.</p>

                        <div style="background: #fff; border: 1px solid #e2e8f0;
                                    border-radius: 8px; padding: 20px; margin: 20px 0;">
                            <p style="color: #64748b; margin: 0 0 8px; font-size: 13px;">YOUR LOGIN CREDENTIALS</p>
                            <p style="color: #1e293b; margin: 0 0 6px;"><strong>Email:</strong> %s</p>
                            <p style="color: #1e293b; margin: 0;"><strong>Temporary Password:</strong>
                                <code style="background: #fee2e2; padding: 3px 8px; border-radius: 4px;
                                             color: #dc2626; font-weight: bold;">%s</code>
                            </p>
                        </div>

                        <div style="background: #fef3c7; border: 1px solid #fde68a;
                                    border-radius: 8px; padding: 16px; margin: 20px 0;">
                            <p style="color: #92400e; margin: 0; font-size: 13px;">
                                ⚠️ <strong>Important:</strong> You will be required to change your
                                password on first login. Please keep this email for reference.
                            </p>
                        </div>

                        <div style="text-align: center; margin: 24px 0;">
                            <a href="%s"
                               style="background: linear-gradient(135deg, #2563eb, #4f46e5);
                                      color: white; padding: 12px 32px; border-radius: 8px;
                                      text-decoration: none; font-weight: 600; display: inline-block;">
                                Login Now →
                            </a>
                        </div>
                    </div>
                    <div style="text-align: center; padding: 20px; color: #94a3b8; font-size: 12px;">
                        © 2026 Faculty Sync Tracker
                    </div>
                </div>
                """;

            helper.setText(String.format(htmlContent,
                firstName, role, to, tempPassword,
                "https://your-app-url.com/login"), true);

            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send welcome email", e);
        }
    }
}
```

---

## 3. React.js Frontend Implementation

See the following files in the `src/` directory:
- `src/services/api.js` - New API endpoints
- `src/context/AuthContext.jsx` - isFirstLogin handling
- `src/pages/ChangePassword.jsx` - Force change password screen
- `src/pages/ChangePassword.css` - Styles
- `src/App.jsx` - Updated routing
- `src/pages/LoginPage.jsx` - First login interception
