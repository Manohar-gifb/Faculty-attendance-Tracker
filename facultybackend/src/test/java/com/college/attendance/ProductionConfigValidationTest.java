package com.college.attendance;

import com.college.attendance.config.ProductionSecurityValidator;
import com.college.attendance.controller.HealthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 Plan 08-01: Production Configuration & Fail-Closed Validation Tests.
 */
class ProductionConfigValidationTest {

    @Test
    @DisplayName("Production profile rejects default development JWT secret (fails closed)")
    void prodProfile_rejectsDefaultJwtSecret() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        setField(validator, "jwtSecret", ProductionSecurityValidator.INSECURE_DEFAULT_JWT_SECRET);
        setField(validator, "internalSecret", "custom-prod-internal-secret-12345");
        setField(validator, "dbPassword", "StrongProdPassword@2026!");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                invokeValidation(validator)
        );
        assertTrue(ex.getMessage().contains("JWT_SECRET is using the insecure development default key"));
    }

    @Test
    @DisplayName("Production profile rejects default development internal face secret")
    void prodProfile_rejectsDefaultInternalSecret() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        setField(validator, "jwtSecret", "custom-prod-jwt-secret-with-adequate-length-256bit");
        setField(validator, "internalSecret", ProductionSecurityValidator.INSECURE_DEFAULT_INTERNAL_SECRET);
        setField(validator, "dbPassword", "StrongProdPassword@2026!");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                invokeValidation(validator)
        );
        assertTrue(ex.getMessage().contains("INTERNAL_SERVICE_SECRET is using the insecure development default secret"));
    }

    @Test
    @DisplayName("Production profile rejects insecure database dictionary passwords")
    void prodProfile_rejectsInsecureDbPassword() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        setField(validator, "jwtSecret", "custom-prod-jwt-secret-with-adequate-length-256bit");
        setField(validator, "internalSecret", "custom-prod-internal-secret-12345");
        setField(validator, "dbPassword", "password");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                invokeValidation(validator)
        );
        assertTrue(ex.getMessage().contains("DB_PASSWORD is using an insecure common dictionary password"));
    }

    @Test
    @DisplayName("Production profile succeeds when strong external secrets are supplied")
    void prodProfile_succeedsWithStrongSecrets() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        setField(validator, "jwtSecret", "TEST_ONLY_FAKE_JWT_SECRET_123456789012345678901234567890");
        setField(validator, "internalSecret", "TEST_ONLY_FAKE_INTERNAL_SECRET_123456789012345678901234567890");
        setField(validator, "dbPassword", "K7#mP9$xL2@vQ5!jN8");

        assertDoesNotThrow(() -> invokeValidation(validator));
    }

    @Test
    @DisplayName("Non-production profile skips strict validation to allow local development")
    void nonProdProfile_allowsDevelopmentDefaults() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        setField(validator, "jwtSecret", ProductionSecurityValidator.INSECURE_DEFAULT_JWT_SECRET);
        setField(validator, "internalSecret", ProductionSecurityValidator.INSECURE_DEFAULT_INTERNAL_SECRET);
        setField(validator, "dbPassword", "password");

        assertDoesNotThrow(() -> invokeValidation(validator));
    }

    @Test
    @DisplayName("HealthController returns HTTP 200 with UP status")
    void healthController_returnsStatusUp() {
        HealthController controller = new HealthController();
        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("faculty-backend", response.getBody().get("service"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeValidation(ProductionSecurityValidator validator) throws Exception {
        java.lang.reflect.Method method = validator.getClass().getDeclaredMethod("validateProductionSecurity");
        method.setAccessible(true);
        try {
            method.invoke(validator);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ite.getCause();
            }
            throw ite;
        }
    }
}
