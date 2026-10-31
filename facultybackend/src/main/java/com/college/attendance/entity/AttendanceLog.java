package com.college.attendance.entity;

import com.college.attendance.enums.AttendanceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendance_logs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "attendance_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "punch_in_time")
    private LocalDateTime punchInTime;

    @Column(name = "punch_out_time")
    private LocalDateTime punchOutTime;

    @Column(name = "working_hours", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal workingHours = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AttendanceStatus status = AttendanceStatus.ABSENT;

    @Column(name = "is_late")
    @Builder.Default
    private Boolean isLate = false;

    @Column(name = "late_minutes")
    @Builder.Default
    private Integer lateMinutes = 0;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "location_accuracy")
    private Double locationAccuracy;

    @Column(name = "distance_from_college")
    private Double distanceFromCollege;

    @Column(name = "face_confidence")
    private Double faceConfidence;

    @Column(name = "verification_status", length = 50)
    private String verificationStatus;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Calculate working hours when punch-out is recorded
     */
    public void calculateWorkingHours() {
        if (punchInTime != null && punchOutTime != null) {
            long minutes = java.time.Duration.between(punchInTime, punchOutTime).toMinutes();
            this.workingHours = BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * Determine if the punch-in was late based on threshold
     */
    public void evaluateLateStatus(int lateThresholdMinutes) {
        if (punchInTime != null) {
            java.time.LocalTime punchTime = punchInTime.toLocalTime();
            java.time.LocalTime standardTime = java.time.LocalTime.of(9, 0); // 9:00 AM standard

            if (punchTime.isAfter(standardTime)) {
                int lateMins = (int) java.time.Duration.between(standardTime, punchTime).toMinutes();
                this.lateMinutes = lateMins;
                this.isLate = lateMins > lateThresholdMinutes;
                if (this.isLate) {
                    this.status = AttendanceStatus.LATE;
                }
            }
        }
    }
}
