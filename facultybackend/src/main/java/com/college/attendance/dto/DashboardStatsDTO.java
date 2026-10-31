package com.college.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardStatsDTO {

    private long totalFaculty;
    private long presentToday;
    private long absentToday;
    private long lateToday;
    private long onLeaveToday;
    private long pendingLeaves;
    private List<DepartmentAttendanceDTO> departmentBreakdown;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DepartmentAttendanceDTO {
        private String departmentName;
        private String departmentCode;
        private long totalFaculty;
        private long present;
        private long absent;
        private long late;
        private long onLeave;
        private double attendancePercentage;
    }
}
