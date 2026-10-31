package com.college.attendance.controller;

import com.college.attendance.dto.TeamMemberDTO;
import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/team")
@RequiredArgsConstructor
public class TeamController {

    private final UserRepository userRepository;
    private final AttendanceLogRepository attendanceLogRepository;

    /**
     * Get all faculty members in the current user's department
     */
    @GetMapping("/department")
    public ResponseEntity<List<TeamMemberDTO>> getDepartmentFaculty(
            @AuthenticationPrincipal UserDetails userDetails) {

        User currentUser = (User) userDetails;

        // Get department ID from current user
        Long departmentId = currentUser.getDepartment() != null
                ? currentUser.getDepartment().getId()
                : null;
        Long currentUserId = currentUser.getId();

        if (departmentId == null && currentUser.getRole() != Role.HOD) {
            // If user has no department and is not HOD, return empty list
            return ResponseEntity.ok(List.of());
        }

        List<User> departmentUsers;
        if (currentUser.getRole() == Role.HOD) {
            departmentUsers = userRepository.findAll().stream()
                    .filter(User::getIsActive)
                    .filter(u -> !u.getId().equals(currentUserId))
                    .filter(u -> u.getRole() == Role.FACULTY)
                    .filter(u -> {
                        boolean inDept = departmentId != null
                                && u.getDepartment() != null
                                && departmentId.equals(u.getDepartment().getId());
                        boolean isAssigned = (u.getLeaveApprover() != null && currentUserId.equals(u.getLeaveApprover().getId()))
                                || (u.getSecondaryApprover() != null && currentUserId.equals(u.getSecondaryApprover().getId()));
                        return inDept || isAssigned;
                    })
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            departmentUsers = userRepository.findByDepartmentId(departmentId)
                    .stream()
                    .filter(User::getIsActive)
                    .filter(u -> u.getRole() != Role.DEVELOPER)
                    .collect(Collectors.toList());
        }

        // Get today's attendance in one fast query
        LocalDate today = LocalDate.now();
        Map<Long, AttendanceLog> todayAttendanceMap = attendanceLogRepository
                .findByAttendanceDate(today)
                .stream()
                .collect(Collectors.toMap(
                        al -> al.getUser().getId(),
                        al -> al,
                        (existing, replacement) -> existing
                ));

        // Map to DTO with clock-in status
        List<TeamMemberDTO> teamMembers = departmentUsers.stream()
                .map(user -> {
                    TeamMemberDTO dto = mapToDTO(user);
                    AttendanceLog todayLog = todayAttendanceMap.get(user.getId());
                    // Clocked in = has punch-in time AND no punch-out time
                    boolean clockedIn = todayLog != null
                            && todayLog.getPunchInTime() != null
                            && todayLog.getPunchOutTime() == null;
                    dto.setIsClockedIn(clockedIn);
                    return dto;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(teamMembers);
    }

    /**
     * Admin: Get faculty across departments (or scoped to assigned faculty for HOD)
     */
    @GetMapping("/all")
    public ResponseEntity<List<TeamMemberDTO>> getAllFaculty(
            @AuthenticationPrincipal UserDetails userDetails) {

        User currentUser = (User) userDetails;

        // Authorized college-wide admin roles only
        boolean isCollegeAdmin = currentUser.getRole() == Role.DEVELOPER
                || currentUser.getRole() == Role.PRINCIPAL
                || currentUser.getRole() == Role.VC
                || currentUser.getRole() == Role.TPO;

        if (!isCollegeAdmin) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }

        // College-wide admins see all active faculty and HOD users across all departments
        List<User> allUsers = userRepository.findAll().stream()
                .filter(User::getIsActive)
                .filter(u -> u.getRole() == Role.FACULTY || u.getRole() == Role.HOD)
                .collect(Collectors.toList());

        // Get today's attendance in ONE query
        LocalDate today = LocalDate.now();
        Map<Long, AttendanceLog> todayAttendanceMap = attendanceLogRepository
                .findByAttendanceDate(today)
                .stream()
                .collect(Collectors.toMap(
                        al -> al.getUser().getId(),
                        al -> al,
                        (existing, replacement) -> existing
                ));

        // Map to DTO with clock-in status
        List<TeamMemberDTO> teamMembers = allUsers.stream()
                .map(user -> {
                    TeamMemberDTO dto = mapToDTO(user);
                    AttendanceLog todayLog = todayAttendanceMap.get(user.getId());
                    boolean clockedIn = todayLog != null
                            && todayLog.getPunchInTime() != null
                            && todayLog.getPunchOutTime() == null;
                    dto.setIsClockedIn(clockedIn);
                    return dto;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(teamMembers);
    }


    /**
     * Map User entity to TeamMemberDTO
     */
    private TeamMemberDTO mapToDTO(User user) {
        return TeamMemberDTO.builder()
                .id(user.getId())
                .employeeId(user.getEmployeeId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole())
                .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : "N/A")
                .departmentCode(user.getDepartment() != null ? user.getDepartment().getCode() : "N/A")
                .profilePictureUrl(user.getProfilePictureUrl())
                .isActive(user.getIsActive())
                .build();
    }
}
