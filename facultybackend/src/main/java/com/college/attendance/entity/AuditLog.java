package com.college.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Immutable audit trail for sensitive administrative actions
 * (account creation, role changes, department changes, activation, deactivation, ...).
 * Never stores passwords, JWTs or activation/reset tokens.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_actor", columnList = "actor_email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_email", length = 100)
    private String actorEmail;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Column(name = "target_type", length = 40)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_email", length = 100)
    private String targetEmail;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
