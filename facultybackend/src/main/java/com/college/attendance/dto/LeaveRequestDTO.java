package com.college.attendance.dto;

import com.college.attendance.enums.LeaveStatus;
import com.college.attendance.enums.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveRequestDTO {

    private Long id;
    private Long userId;
    private String facultyName;
    private String employeeId;
    private String departmentName;

    @NotNull(message = "Leave type is required")
    private LeaveType leaveType;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    @NotBlank(message = "Reason is required")
    private String reason;

    private String attachmentUrl;
    private LeaveStatus status;
    private String reviewerComments;
    private LocalDateTime reviewedAt;
    private String approvedByName;
    private int durationDays;
    private LocalDateTime createdAt;
}
