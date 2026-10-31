package com.college.attendance.entity;

import com.college.attendance.enums.Role;
import com.college.attendance.enums.TokenPurpose;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, unique = true, length = 20)
    private String employeeId;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_approver_id")
    private User leaveApprover;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "secondary_approver_id")
    private User secondaryApprover;

    @Column(length = 20)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_picture_url", length = 500)
    private String profilePictureUrl;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * false = fully active account. true = must customize the password at
     * next login (set explicitly by DeveloperService at account creation).
     * Default is false so Lombok builders that omit it store FALSE instead of
     * NULL (the historic DataSeeder bug that left 9 rows NULL).
     */
    @Column(name = "first_login")
    @Builder.Default
    private Boolean firstLogin = false;

    @JsonIgnore
    @Column(name = "password_setup_token", length = 255)
    private String passwordSetupToken;

    @JsonIgnore
    @Column(name = "password_setup_token_expiry")
    private LocalDateTime passwordSetupTokenExpiry;

    /** ACTIVATION (first-time setup) or PASSWORD_RESET — never both at once. */
    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(name = "password_setup_token_purpose", length = 20)
    private TokenPurpose passwordSetupTokenPurpose;

    /** When the current single-use token was issued (token creation time). */
    @JsonIgnore
    @Column(name = "password_setup_token_created_at")
    private LocalDateTime passwordSetupTokenCreatedAt;

    /** When the current single-use token was consumed (usage status). */
    @JsonIgnore
    @Column(name = "password_setup_token_used_at")
    private LocalDateTime passwordSetupTokenUsedAt;

    /** When the account completed first-time activation. */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // UserDetails implementation
    @Override
    @Transient
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    @Transient
    public String getPassword() {
        return passwordHash;
    }

    @Override
    @Transient
    public String getUsername() {
        return email;
    }

    @Override
    @Transient
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @Transient
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    @Transient
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    @Transient
    public boolean isEnabled() {
        return isActive;
    }

    @Transient
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
