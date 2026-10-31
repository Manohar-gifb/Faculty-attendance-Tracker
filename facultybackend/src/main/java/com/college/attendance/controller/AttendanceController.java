package com.college.attendance.controller;

import com.college.attendance.dto.*;
import com.college.attendance.entity.User;
import com.college.attendance.service.AttendanceService;
import com.college.attendance.service.FaceVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final FaceVerificationService faceVerificationService;

    /**
     * Health check for face verification microservice
     */
    @GetMapping("/face-service/health")
    public ResponseEntity<Map<String, Object>> getFaceServiceHealth() {
        return ResponseEntity.ok(faceVerificationService.checkServiceHealth());
    }

    /**
     * Verify attendance: face match + location check
     */
    @PostMapping("/verify")
    public ResponseEntity<VerifyAttendanceResponse> verifyAttendance(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody VerifyAttendanceRequest request) {
        User user = (User) userDetails;
        VerifyAttendanceResponse response = faceVerificationService.verify(user.getId(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * Punch In - Faculty records their arrival (after verification).
     * Body (optional): {"verificationTicket": "..."} — required for all
     * roles except DEVELOPER; ticket comes from POST /attendance/verify.
     */
    @PostMapping("/punch-in")
    public ResponseEntity<AttendanceDTO> punchIn(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) Map<String, String> body) {
        User user = (User) userDetails;
        AttendanceDTO dto = attendanceService.punchIn(user.getId(),
                body != null ? body.get("verificationTicket") : null);
        return ResponseEntity.ok(dto);
    }

    /**
     * Punch Out - Faculty records their departure
     */
    @PostMapping("/punch-out")
    public ResponseEntity<AttendanceDTO> punchOut(@AuthenticationPrincipal UserDetails userDetails) {
        User user = (User) userDetails;
        AttendanceDTO dto = attendanceService.punchOut(user.getId());
        return ResponseEntity.ok(dto);
    }

    /**
     * Get today's attendance for the logged-in faculty
     */
    @GetMapping("/today")
    public ResponseEntity<AttendanceDTO> getTodayAttendance(@AuthenticationPrincipal UserDetails userDetails) {
        User user = (User) userDetails;
        AttendanceDTO dto = attendanceService.getTodayAttendance(user.getId());
        return ResponseEntity.ok(dto);
    }

    /**
     * Get attendance history for the logged-in faculty
     */
    @GetMapping("/history")
    public ResponseEntity<List<AttendanceDTO>> getAttendanceHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        User user = (User) userDetails;
        List<AttendanceDTO> history = attendanceService.getAttendanceHistory(user.getId(), startDate, endDate);
        return ResponseEntity.ok(history);
    }

    /**
     * Admin: Get today's attendance for all faculty
     *
     * College-wide scope (DEVELOPER / VC / PRINCIPAL) supports optional DB-level
     * pagination: add `page` and `size` query params to receive
     * { content, page, size, totalElements, totalPages } instead of the full
     * list (the no-params default stays backward compatible).
     */
    @GetMapping("/admin/all")
    public ResponseEntity<?> getAllTodayAttendance(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        User user = (User) userDetails;
        if (user != null && user.getRole() != com.college.attendance.enums.Role.DEVELOPER
                && user.getRole() != com.college.attendance.enums.Role.PRINCIPAL
                && user.getRole() != com.college.attendance.enums.Role.VC) {
            if (user.getDepartment() == null) {
                return ResponseEntity.ok(List.of());
            }
            return ResponseEntity.ok(attendanceService.getDepartmentAttendance(user.getDepartment().getId()));
        }
        if (page != null && size != null) {
            return ResponseEntity.ok(attendanceService.getTodayAttendancePage(page, size));
        }
        List<AttendanceDTO> allAttendance = attendanceService.getTodayAttendanceForAll();
        return ResponseEntity.ok(allAttendance);
    }

    /**
     * Admin: Get department-wise attendance
     */
    @GetMapping("/admin/department/{departmentId}")
    public ResponseEntity<List<AttendanceDTO>> getDepartmentAttendance(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("departmentId") Long departmentId) {
        User user = (User) userDetails;
        if (user != null && user.getRole() != com.college.attendance.enums.Role.DEVELOPER
                && user.getRole() != com.college.attendance.enums.Role.PRINCIPAL
                && user.getRole() != com.college.attendance.enums.Role.VC) {
            if (user.getDepartment() == null || !user.getDepartment().getId().equals(departmentId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
            }
        }
        List<AttendanceDTO> deptAttendance = attendanceService.getDepartmentAttendance(departmentId);
        return ResponseEntity.ok(deptAttendance);
    }

    /**
     * Admin: Get dashboard statistics
     */
    @GetMapping("/admin/stats")
    public ResponseEntity<DashboardStatsDTO> getDashboardStats(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = (User) userDetails;
        DashboardStatsDTO stats = attendanceService.getDashboardStats(user);
        return ResponseEntity.ok(stats);
    }

    /**
     * Admin/Developer: Get college attendance location configuration
     */
    @GetMapping("/admin/location-config")
    public ResponseEntity<LocationConfigDTO> getLocationConfig() {
        return ResponseEntity.ok(faceVerificationService.getLocationConfig());
    }

    /**
     * Admin/Developer: Update college attendance location configuration
     */
    @PutMapping("/admin/location-config")
    public ResponseEntity<LocationConfigDTO> updateLocationConfig(
            @AuthenticationPrincipal UserDetails userDetails,
            @jakarta.validation.Valid @RequestBody LocationConfigDTO config) {
        User user = (User) userDetails;
        if (user.getRole() != com.college.attendance.enums.Role.DEVELOPER
                && user.getRole() != com.college.attendance.enums.Role.PRINCIPAL
                && user.getRole() != com.college.attendance.enums.Role.VC) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        faceVerificationService.updateLocationConfig(config);
        return ResponseEntity.ok(faceVerificationService.getLocationConfig());
    }
}
