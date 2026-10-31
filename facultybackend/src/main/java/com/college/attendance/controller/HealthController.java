package com.college.attendance.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Lightweight unauthenticated health probe for container orchestration
 * and infrastructure monitoring (Docker, Kubernetes, AWS ECS, etc.).
 *
 * Mapped to /health (/api/health with context-path /api).
 *
 * The probe verifies actual MySQL datasource connectivity before reporting
 * UP: an unreachable database yields HTTP 503 with status DOWN so the
 * orchestration layer restarts or reschedules the container instead of
 * routing traffic to a backend that cannot load its data.
 */
@RestController
public class HealthController {

    private final DataSource dataSource;

    @Autowired
    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Convenience constructor for plain unit tests (Spring always injects the real DataSource at runtime). */
    public HealthController() {
        this.dataSource = null;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "faculty-backend");
        body.put("timestamp", System.currentTimeMillis());

        String dbStatus;
        String dbError = null;
        if (dataSource == null) {
            // Only reachable in plain unit tests; Spring always injects the
            // datasource in a real application context.
            dbStatus = "NOT_CONFIGURED";
        } else {
            try (Connection connection = dataSource.getConnection()) {
                // isValid() issues a lightweight driver ping — no query metadata needed.
                dbStatus = connection.isValid(3) ? "UP" : "DOWN";
            } catch (Exception e) {
                //Never leak driver/JDBC internals into the response body.
                dbStatus = "DOWN";
                dbError = "Database connection check failed";
            }
        }
        body.put("database", dbStatus);

        boolean healthy = dataSource == null || "UP".equals(dbStatus);
        if (!healthy) {
            body.put("status", "DOWN");
            return ResponseEntity.status(503).body(body);
        }

        body.put("status", "UP");
        return ResponseEntity.ok(body);
    }
}
