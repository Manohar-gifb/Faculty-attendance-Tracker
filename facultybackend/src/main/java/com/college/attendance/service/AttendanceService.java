package com.college.attendance.service;

import com.college.attendance.dto.AttendanceDTO;
import com.college.attendance.dto.DashboardStatsDTO;
import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.AttendanceStatus;
import com.college.attendance.enums.Role;
import com.college.attendance.exception.DuplicateAttendanceException;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceLogRepository attendanceLogRepository;
    private final UserRepository userRepository;
    private final com.college.attendance.repository.LeaveRequestRepository leaveRequestRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.attendance.late-threshold-minutes:15}")
    private int lateThresholdMinutes;

    private final FaceVerificationService faceVerificationService;

    /**
     * Punch In for the current day.
     *
     * Non-DEVELOPER roles must present a valid single-use verification ticket
     * issued by POST /attendance/verify (face + GPS), so punch-in cannot be
     * called directly without successfully passing verification.
     */
    @Transactional
    public AttendanceDTO punchIn(Long userId, String verificationTicket) {
        LocalDate today = LocalDate.now();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        FaceVerificationService.VerificationTicket ticketDetails = null;
        if (user.getRole() != Role.DEVELOPER) {
            ticketDetails = faceVerificationService.consumeTicketDetails(userId, verificationTicket);
            if (ticketDetails == null) {
                log.warn("Punch-in denied for user {}: missing or invalid verification ticket", user.getEmail());
                throw new RuntimeException(
                        "Face and location verification are required before punching in. "
                                + "Please use the 'Verify & clock in' flow (camera + location). "
                                + "If the verification just passed, the ticket may have expired — verify again.");
            }
        }

        AttendanceLog existingLog = attendanceLogRepository
                .findByUserIdAndAttendanceDate(userId, today)
                .orElse(null);

        if (existingLog != null && existingLog.getPunchInTime() != null) {
            throw new RuntimeException("Already punched in for today");
        }

        AttendanceLog log;
        if (existingLog != null) {
            log = existingLog;
        } else {
            log = AttendanceLog.builder()
                    .user(user)
                    .attendanceDate(today)
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();
        log.setPunchInTime(now);
        log.setStatus(AttendanceStatus.PRESENT);
        log.evaluateLateStatus(lateThresholdMinutes);

        if (ticketDetails != null) {
            log.setLatitude(ticketDetails.latitude());
            log.setLongitude(ticketDetails.longitude());
            log.setLocationAccuracy(ticketDetails.accuracy());
            log.setDistanceFromCollege(ticketDetails.distanceFromCollege());
            log.setFaceConfidence(ticketDetails.faceConfidence());
            log.setVerificationStatus("VERIFIED");
        } else if (user.getRole() == Role.DEVELOPER) {
            log.setVerificationStatus("EXEMPT_DEVELOPER");
        }

        try {
            attendanceLogRepository.save(log);
            attendanceLogRepository.flush();
        } catch (DataIntegrityViolationException dive) {
            // Two concurrent punch-ins can both pass the existence check above and
            // race on the uk_attendance_user_date unique constraint. Re-check for a
            // duplicate in a NEW transaction: the failed insert leaves the current
            // Hibernate session corrupted, so an in-transaction read would throw
            // HHH000099 AssertionFailure and leak internal state. A fresh read
            // transaction sees only committed data. Only when the day's row really
            // exists do we label it duplicate attendance; otherwise the original
            // integrity violation is rethrown untouched so unrelated constraint
            // errors are never mislabeled.
            TransactionTemplate duplicateCheckTx = new TransactionTemplate(transactionManager);
            duplicateCheckTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            duplicateCheckTx.setReadOnly(true);
            boolean duplicateRow = Boolean.TRUE.equals(duplicateCheckTx.execute(status ->
                    attendanceLogRepository.findByUserIdAndAttendanceDate(userId, today).isPresent()));
            if (duplicateRow) {
                this.log.warn("Concurrent punch-in race detected for user {}: duplicate attendance denied", user.getEmail());
                throw new DuplicateAttendanceException("Attendance record already exists for today");
            }
            throw dive;
        }
        this.log.info("Punch in recorded for user: {} at {} with status {}", user.getEmail(), now, log.getVerificationStatus());

        return mapToDTO(log);
    }

    /**
     * Punch Out for the current day
     */
    @Transactional
    public AttendanceDTO punchOut(Long userId) {
        LocalDate today = LocalDate.now();

        AttendanceLog log = attendanceLogRepository
                .findByUserIdAndAttendanceDate(userId, today)
                .orElseThrow(() -> new RuntimeException("No punch-in record found for today. Please punch in first."));

        if (log.getPunchOutTime() != null) {
            throw new RuntimeException("Already punched out for today");
        }

        LocalDateTime now = LocalDateTime.now();
        log.setPunchOutTime(now);
        log.calculateWorkingHours();

        // If late but left early, mark as half day
        if (log.getWorkingHours() != null && log.getWorkingHours().doubleValue() < 4.0) {
            log.setStatus(AttendanceStatus.HALF_DAY);
        }

        attendanceLogRepository.save(log);
        this.log.info("Punch out recorded for user: {} at {} | Working hours: {}",
                log.getUser().getEmail(), now, log.getWorkingHours());

        return mapToDTO(log);
    }

    /**
     * Get today's attendance for a user
     */
    public AttendanceDTO getTodayAttendance(Long userId) {
        LocalDate today = LocalDate.now();
        AttendanceLog log = attendanceLogRepository
                .findByUserIdAndAttendanceDate(userId, today)
                .orElse(null);

        if (log == null) {
            boolean onApprovedLeave = !leaveRequestRepository.findOverlappingLeaves(userId, today, today).stream()
                    .filter(l -> l.getStatus() == com.college.attendance.enums.LeaveStatus.APPROVED)
                    .toList().isEmpty();

            return AttendanceDTO.builder()
                    .userId(userId)
                    .attendanceDate(today)
                    .status(onApprovedLeave ? AttendanceStatus.ON_LEAVE : AttendanceStatus.ABSENT)
                    .hasPunchedIn(false)
                    .hasPunchedOut(false)
                    .build();
        }

        AttendanceDTO dto = mapToDTO(log);
        dto.setHasPunchedIn(log.getPunchInTime() != null);
        dto.setHasPunchedOut(log.getPunchOutTime() != null);
        return dto;
    }

    /**
     * Get attendance history for a user
     */
    public List<AttendanceDTO> getAttendanceHistory(Long userId, LocalDate startDate, LocalDate endDate) {
        List<AttendanceLog> logs = attendanceLogRepository
                .findByUserIdAndAttendanceDateBetween(userId, startDate, endDate);
        return logs.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Get today's attendance for all faculty (admin view)
     */
    public List<AttendanceDTO> getTodayAttendanceForAll() {
        LocalDate today = LocalDate.now();
        List<AttendanceLog> logs = attendanceLogRepository.findByAttendanceDate(today);

        // Get all active faculty
        List<User> allFaculty = userRepository.findAllActiveFaculty();
        List<AttendanceDTO> result = new ArrayList<>();

        // Create a map of existing attendance logs
        var logMap = logs.stream()
                .collect(Collectors.toMap(l -> l.getUser().getId(), l -> l, (a, b) -> b));

        for (User faculty : allFaculty) {
            result.add(attendanceEntryFor(faculty, logMap.get(faculty.getId()), today));
        }

        return result;
    }

    /** Enrich one faculty member with today's log (or an ABSENT placeholder when none exists). */
    private AttendanceDTO attendanceEntryFor(User faculty, AttendanceLog log, LocalDate today) {
        if (log != null) {
            return mapToDTO(log);
        }
        return AttendanceDTO.builder()
                .userId(faculty.getId())
                .facultyName(faculty.getFullName())
                .employeeId(faculty.getEmployeeId())
                .departmentName(faculty.getDepartment() != null ? faculty.getDepartment().getName() : "N/A")
                .attendanceDate(today)
                .status(AttendanceStatus.ABSENT)
                .hasPunchedIn(false)
                .hasPunchedOut(false)
                .build();
    }

    /**
     * Paginated college-wide view of today's attendance
     * (GET /attendance/admin/all?page=&amp;size=).
     * Only the faculty members on the requested page and their attendance logs
     * are loaded from the database, keeping memory and latency bounded for
     * large institutions instead of materialising every roster entry at once.
     */
    public Map<String, Object> getTodayAttendancePage(int page, int size) {
        LocalDate today = LocalDate.now();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.ASC, "fullName"));

        Page<User> facultyPage = userRepository.findAllActiveFaculty(pageable);
        Set<Long> facultyIds = facultyPage.getContent().stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        Map<Long, AttendanceLog> logMap = attendanceLogRepository.findByUserIdInAndAttendanceDate(facultyIds, today)
                .stream()
                .collect(Collectors.toMap(l -> l.getUser().getId(), l -> l, (a, b) -> b));

        List<AttendanceDTO> content = new ArrayList<>();
        for (User faculty : facultyPage.getContent()) {
            content.add(attendanceEntryFor(faculty, logMap.get(faculty.getId()), today));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", facultyPage.getNumber());
        result.put("size", facultyPage.getSize());
        result.put("totalElements", facultyPage.getTotalElements());
        result.put("totalPages", facultyPage.getTotalPages());
        return result;
    }

    /**
     * Get department-wise attendance (admin view)
     */
    public List<AttendanceDTO> getDepartmentAttendance(Long departmentId) {
        LocalDate today = LocalDate.now();
        List<AttendanceLog> logs = attendanceLogRepository.findByDateAndDepartment(today, departmentId);
        List<User> deptFaculty = userRepository.findActiveFacultyByDepartment(departmentId);
        var logMap = logs.stream()
                .collect(Collectors.toMap(l -> l.getUser().getId(), l -> l, (a, b) -> b));

        List<AttendanceDTO> result = new ArrayList<>();
        for (User faculty : deptFaculty) {
            result.add(attendanceEntryFor(faculty, logMap.get(faculty.getId()), today));
        }
        return result;
    }

    /**
     * Get dashboard statistics for admin.
     * Read-only transaction so lazy department proxies of detached callers
     * initialize cleanly instead of throwing LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public DashboardStatsDTO getDashboardStats() {
        LocalDate today = LocalDate.now();
        List<User> allFaculty = userRepository.findAllActiveFaculty();

        long totalFaculty = allFaculty.size();
        long present = attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.PRESENT);
        long late = attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.LATE);
        long halfDay = attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.HALF_DAY);
        long onLeaveFromLogs = attendanceLogRepository.countByDateAndStatus(today, AttendanceStatus.ON_LEAVE);
        long approvedLeavesToday = leaveRequestRepository.countApprovedLeavesOnDate(today);
        long onLeave = Math.max(onLeaveFromLogs, approvedLeavesToday);
        long absent = Math.max(0, totalFaculty - present - late - halfDay - onLeave);

        // Department breakdown
        List<DashboardStatsDTO.DepartmentAttendanceDTO> deptBreakdown = new ArrayList<>();
        var departments = allFaculty.stream()
                .filter(u -> u.getDepartment() != null)
                .collect(Collectors.groupingBy(User::getDepartment));

        for (var entry : departments.entrySet()) {
            long deptTotal = entry.getValue().size();
            long deptPresent = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                    today, entry.getKey().getId(), AttendanceStatus.PRESENT);
            long deptLate = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                    today, entry.getKey().getId(), AttendanceStatus.LATE);
            long deptHalfDay = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                    today, entry.getKey().getId(), AttendanceStatus.HALF_DAY);
            long deptOnLeave = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                    today, entry.getKey().getId(), AttendanceStatus.ON_LEAVE);
            long deptAbsent = Math.max(0, deptTotal - deptPresent - deptLate - deptHalfDay - deptOnLeave);

            double percentage = deptTotal > 0 ? (deptPresent + deptLate) * 100.0 / deptTotal : 0;

            deptBreakdown.add(DashboardStatsDTO.DepartmentAttendanceDTO.builder()
                    .departmentName(entry.getKey().getName())
                    .departmentCode(entry.getKey().getCode())
                    .totalFaculty(deptTotal)
                    .present(deptPresent)
                    .absent(deptAbsent)
                    .late(deptLate)
                    .attendancePercentage(Math.round(percentage * 100.0) / 100.0)
                    .build());
        }

        return DashboardStatsDTO.builder()
                .totalFaculty(totalFaculty)
                .presentToday(present)
                .absentToday(absent)
                .lateToday(late)
                .onLeaveToday(onLeave)
                .pendingLeaves(leaveRequestRepository.countPendingLeaves())
                .departmentBreakdown(deptBreakdown)
                .build();
    }

    /**
     * Get dashboard statistics scoped by caller role and department.
     * Institutional leaders (DEVELOPER, PRINCIPAL, VC) receive college-wide statistics.
     * Department-bound roles (HOD, or TPO assigned to a department) receive strictly scoped statistics.
     */
    @Transactional(readOnly = true)
    public DashboardStatsDTO getDashboardStats(User caller) {
        if (caller == null || caller.getRole() == Role.DEVELOPER
                || caller.getRole() == Role.PRINCIPAL
                || caller.getRole() == Role.VC) {
            return getDashboardStats();
        }

        Department dept = caller.getDepartment();
        if (dept == null) {
            return DashboardStatsDTO.builder()
                    .totalFaculty(0)
                    .presentToday(0)
                    .absentToday(0)
                    .lateToday(0)
                    .onLeaveToday(0)
                    .pendingLeaves(0)
                    .departmentBreakdown(List.of())
                    .build();
        }

        LocalDate today = LocalDate.now();
        List<User> deptFaculty = userRepository.findAll().stream()
                .filter(User::getIsActive)
                .filter(u -> u.getRole() == Role.FACULTY)
                .filter(u -> (u.getDepartment() != null && u.getDepartment().getId().equals(dept.getId()))
                        || (u.getLeaveApprover() != null && caller.getId().equals(u.getLeaveApprover().getId()))
                        || (u.getSecondaryApprover() != null && caller.getId().equals(u.getSecondaryApprover().getId())))
                .distinct()
                .collect(Collectors.toList());

        long deptTotal = deptFaculty.size();
        long deptPresent = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                today, dept.getId(), AttendanceStatus.PRESENT);
        long deptLate = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                today, dept.getId(), AttendanceStatus.LATE);
        long deptHalfDay = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                today, dept.getId(), AttendanceStatus.HALF_DAY);
        long deptOnLeaveLogs = attendanceLogRepository.countByDateAndDepartmentAndStatus(
                today, dept.getId(), AttendanceStatus.ON_LEAVE);
        long deptApprovedLeaves = leaveRequestRepository.findByDepartmentIdAndStatus(dept.getId(), com.college.attendance.enums.LeaveStatus.APPROVED).stream()
                .filter(lr -> !today.isBefore(lr.getStartDate()) && !today.isAfter(lr.getEndDate()))
                .count();
        long deptOnLeave = Math.max(deptOnLeaveLogs, deptApprovedLeaves);
        long deptAbsent = Math.max(0, deptTotal - deptPresent - deptLate - deptHalfDay - deptOnLeave);
        long pendingLeaves = leaveRequestRepository.findByDepartmentIdAndStatus(dept.getId(), com.college.attendance.enums.LeaveStatus.PENDING).size();

        double percentage = deptTotal > 0 ? (deptPresent + deptLate) * 100.0 / deptTotal : 0;

        DashboardStatsDTO.DepartmentAttendanceDTO deptDto = DashboardStatsDTO.DepartmentAttendanceDTO.builder()
                .departmentName(dept.getName())
                .departmentCode(dept.getCode())
                .totalFaculty(deptTotal)
                .present(deptPresent)
                .absent(deptAbsent)
                .late(deptLate)
                .attendancePercentage(Math.round(percentage * 100.0) / 100.0)
                .build();

        return DashboardStatsDTO.builder()
                .totalFaculty(deptTotal)
                .presentToday(deptPresent)
                .absentToday(deptAbsent)
                .lateToday(deptLate)
                .onLeaveToday(deptOnLeave)
                .pendingLeaves(pendingLeaves)
                .departmentBreakdown(List.of(deptDto))
                .build();
    }

    /**
     * Map AttendanceLog entity to AttendanceDTO
     */
    private AttendanceDTO mapToDTO(AttendanceLog log) {
        return AttendanceDTO.builder()
                .id(log.getId())
                .userId(log.getUser().getId())
                .facultyName(log.getUser().getFullName())
                .employeeId(log.getUser().getEmployeeId())
                .departmentName(log.getUser().getDepartment() != null ?
                        log.getUser().getDepartment().getName() : "N/A")
                .attendanceDate(log.getAttendanceDate())
                .punchInTime(log.getPunchInTime())
                .punchOutTime(log.getPunchOutTime())
                .workingHours(log.getWorkingHours())
                .status(log.getStatus())
                .isLate(log.getIsLate())
                .lateMinutes(log.getLateMinutes())
                .remarks(log.getRemarks())
                .latitude(log.getLatitude())
                .longitude(log.getLongitude())
                .locationAccuracy(log.getLocationAccuracy())
                .distanceFromCollege(log.getDistanceFromCollege())
                .faceConfidence(log.getFaceConfidence())
                .verificationStatus(log.getVerificationStatus())
                .hasPunchedIn(log.getPunchInTime() != null)
                .hasPunchedOut(log.getPunchOutTime() != null)
                .build();
    }
}
