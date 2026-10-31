package com.college.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeveloperStatsDTO {

    // Account totals (computed in the database)
    private long total;
    private long faculty;
    private long admins;
    private long departments;
    private long active;
    private long inactive;
    private long pendingActivations;

    // Per-role account counts (FACULTY, HOD, PRINCIPAL, VC, TPO, DEVELOPER)
    private Map<String, Long> roleCounts;

    // Department-wise user statistics
    private List<DepartmentStat> departmentWise;

    // Most recently created accounts
    private List<RecentAccount> recentAccounts;

    // Most recent administrative actions from the audit trail
    private List<AuditEntry> recentActions;

    // Real attendance system status for today (read from attendance_logs)
    private AttendanceStatus attendance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartmentStat {
        private Long departmentId;
        private String name;
        private String code;
        private Boolean isActive;
        private long totalUsers;
        private long facultyCount;
        private long activeUsers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentAccount {
        private Long id;
        private String fullName;
        private String email;
        private String role;
        private String departmentName;
        private String activationStatus;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditEntry {
        private Long id;
        private String action;
        private String actorEmail;
        private String targetType;
        private String targetEmail;
        private String details;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttendanceStatus {
        private LocalDate date;
        private long activeFaculty;
        private long marked;
        private long present;
        private long late;
        private long absent;
        private long onLeave;
        private long halfDay;
    }
}
