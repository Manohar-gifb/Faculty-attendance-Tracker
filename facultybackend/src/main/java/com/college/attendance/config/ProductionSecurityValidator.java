package com.college.attendance.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 8 Plan 08-01: Production Secret & Security Validator.
 *
 * Ensures that when the "prod" profile is active, the application fails closed
 * on startup if any insecure default development secrets or passwords are detected.
 */
@Slf4j
@Component
public class ProductionSecurityValidator implements ApplicationRunner {

    public static final String INSECURE_DEFAULT_JWT_SECRET =
            "ZmVsdHliYWNrZW5kYXR0ZW5kYW5kbWFuYWdlbWVudHN5c3RlbXNlY3JldGtleTIwMjRzdXBlcnNlY3VyZQ==";
    public static final String INSECURE_DEFAULT_INTERNAL_SECRET =
            "faculty_internal_face_secret_key_2026";
    public static final List<String> INSECURE_DB_PASSWORDS =
            List.of("password", "root", "admin", "123456", "12345678");

    private final Environment environment;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${face-verification.internal-secret:}")
    private String internalSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    public ProductionSecurityValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateProductionSecurity();
    }

    /**
     * Validates configuration when "prod" profile is active.
     * Package-private to allow direct unit/integration testing without booting full container.
     */
    void validateProductionSecurity() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            log.debug("Non-production profile active. Skipping strict production secret validation.");
            return;
        }

        log.info("Production profile detected. Running strict security & secret validation...");
        List<String> violations = new ArrayList<>();

        // 1. JWT Secret Validation
        if (jwtSecret == null || jwtSecret.isBlank()) {
            violations.add("JWT_SECRET (app.jwt.secret) must not be empty in production.");
        } else if (INSECURE_DEFAULT_JWT_SECRET.equals(jwtSecret.trim())) {
            violations.add("JWT_SECRET is using the insecure development default key. A unique cryptographically strong 256-bit secret must be provided via the JWT_SECRET environment variable.");
        } else if (jwtSecret.trim().length() < 32) {
            violations.add("JWT_SECRET must be at least 32 characters (256-bit entropy) in production.");
        }

        // 2. Inter-service Shared Secret Validation
        if (internalSecret == null || internalSecret.isBlank()) {
            violations.add("INTERNAL_SERVICE_SECRET (face-verification.internal-secret) must not be empty in production.");
        } else if (INSECURE_DEFAULT_INTERNAL_SECRET.equals(internalSecret.trim())) {
            violations.add("INTERNAL_SERVICE_SECRET is using the insecure development default secret. A unique secret must be configured via the INTERNAL_SERVICE_SECRET environment variable.");
        } else if (internalSecret.trim().length() < 16) {
            violations.add("INTERNAL_SERVICE_SECRET must be at least 16 characters in production.");
        }

        // 3. Database Password Validation
        if (dbPassword == null || dbPassword.isBlank()) {
            violations.add("DB_PASSWORD (spring.datasource.password) must not be empty in production.");
        } else if (INSECURE_DB_PASSWORDS.contains(dbPassword.trim().toLowerCase())) {
            violations.add("DB_PASSWORD is using an insecure common dictionary password ('" + dbPassword + "'). Set a strong password via the DB_PASSWORD environment variable.");
        }

        if (!violations.isEmpty()) {
            String errorMessage = "PRODUCTION SECURITY VALIDATION FAILED - Refusing to boot with insecure configuration:\n"
                    + String.join("\n - ", violations);
            log.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        log.info("Production security validation PASSED: all required secrets are non-default and adequately sized.");
    }
}
