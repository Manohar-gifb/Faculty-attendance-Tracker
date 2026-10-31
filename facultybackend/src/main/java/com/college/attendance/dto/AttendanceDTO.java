package com.college.attendance.dto;

import com.college.attendance.enums.AttendanceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceDTO {

    private Long id;
    private Long userId;
    private String facultyName;
    private String employeeId;
    private String departmentName;
    private LocalDate attendanceDate;
    private LocalDateTime punchInTime;
    private LocalDateTime punchOutTime;
    private BigDecimal workingHours;
    private AttendanceStatus status;
    private Boolean isLate;
    private Integer lateMinutes;
    private String remarks;
    private Double latitude;
    private Double longitude;
    private Double locationAccuracy;
    private Double distanceFromCollege;
    private Double faceConfidence;
    private String verificationStatus;
    private Boolean hasPunchedIn;
    private Boolean hasPunchedOut;
}
