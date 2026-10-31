package com.college.attendance.service;

import com.college.attendance.entity.AuditLog;
import com.college.attendance.entity.User;
import com.college.attendance.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records sensitive administrative actions in the audit_logs table.
 * Never pass passwords, JWTs, activation tokens or reset tokens as details.
 *
 * Uses REQUIRES_NEW on purpose so an audit row survives the two situations an
 * audit trail must capture:
 *   - the business transaction rolls back (e.g. a rejected activation attempt),
 *   - the entry is written from an afterCommit callback (e.g. the real SMTP
 *     delivery outcome of an activation email).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private static final int MAX_DETAILS_LENGTH = 1000;

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Long targetId, String targetEmail, String details) {
        try {
            String actorEmail = null;
            String actorRole = null;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof User actor) {
                actorEmail = actor.getEmail();
                actorRole = actor.getRole() != null ? actor.getRole().name() : null;
            } else if (authentication != null) {
                actorEmail = authentication.getName();
            }

            String safeDetails = details;
            if (safeDetails != null && safeDetails.length() > MAX_DETAILS_LENGTH) {
                safeDetails = safeDetails.substring(0, MAX_DETAILS_LENGTH);
            }

            auditLogRepository.save(AuditLog.builder()
                    .actorEmail(actorEmail)
                    .actorRole(actorRole)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetEmail(targetEmail)
                    .details(safeDetails)
                    .build());
        } catch (Exception e) {
            // Auditing must never break the business operation, but the failure is logged loudly.
            log.error("Failed to write audit log for action '{}': {}", action, e.getMessage());
        }
    }
}
