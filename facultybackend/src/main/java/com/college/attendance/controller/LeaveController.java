package com.college.attendance.controller;

import com.college.attendance.dto.LeaveRequestDTO;
import com.college.attendance.entity.User;
import com.college.attendance.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    /**
     * Apply for leave - Faculty submits leave request
     */
    @PostMapping("/apply")
    public ResponseEntity<LeaveRequestDTO> applyLeave(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody LeaveRequestDTO request) {
        User user = (User) userDetails;
        LeaveRequestDTO dto = leaveService.applyLeave(user.getId(), request);
        return ResponseEntity.ok(dto);
    }

    /**
     * Get my leave requests
     */
    @GetMapping("/my-leaves")
    public ResponseEntity<List<LeaveRequestDTO>> getMyLeaves(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = (User) userDetails;
        List<LeaveRequestDTO> leaves = leaveService.getUserLeaves(user.getId());
        return ResponseEntity.ok(leaves);
    }

    /**
     * Cancel a pending leave request
     */
    @PostMapping("/cancel/{leaveId}")
    public ResponseEntity<LeaveRequestDTO> cancelLeave(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("leaveId") Long leaveId) {
        User user = (User) userDetails;
        LeaveRequestDTO dto = leaveService.cancelLeave(user.getId(), leaveId);
        return ResponseEntity.ok(dto);
    }

    /**
     * Admin: Get pending leave requests for review
     */
    @GetMapping("/admin/pending")
    public ResponseEntity<List<LeaveRequestDTO>> getPendingLeaves(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(value = "departmentId", required = false) Long departmentId) {
        User user = (User) userDetails;
        if (user != null && user.getRole() != com.college.attendance.enums.Role.DEVELOPER
                && user.getRole() != com.college.attendance.enums.Role.PRINCIPAL
                && user.getRole() != com.college.attendance.enums.Role.VC) {
            if (user.getDepartment() == null) {
                return ResponseEntity.ok(List.of());
            }
            if (departmentId != null && !user.getDepartment().getId().equals(departmentId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
            }
            if (user.getRole() == com.college.attendance.enums.Role.HOD) {
                List<LeaveRequestDTO> pending = leaveService.getPendingLeavesForApprover(user.getId());
                return ResponseEntity.ok(pending);
            }
            departmentId = user.getDepartment().getId();
        }
        List<LeaveRequestDTO> pending = leaveService.getPendingLeaves(departmentId);
        return ResponseEntity.ok(pending);
    }

    /**
     * Admin: Get all leaves for a department
     */
    @GetMapping("/admin/department/{departmentId}")
    public ResponseEntity<List<LeaveRequestDTO>> getDepartmentLeaves(
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
        List<LeaveRequestDTO> leaves = leaveService.getDepartmentLeaves(departmentId);
        return ResponseEntity.ok(leaves);
    }

    // ================================================================
    // ADMIN: APPROVE/REJECT FROM WEBSITE INBOX
    // ================================================================

    /**
     * Admin: Get pending leaves for approver's role/department
     */
    @GetMapping("/admin/pending-for-me")
    public ResponseEntity<List<LeaveRequestDTO>> getPendingLeavesForMe(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = (User) userDetails;
        List<LeaveRequestDTO> pending = leaveService.getPendingLeavesForApprover(user.getId());
        return ResponseEntity.ok(pending);
    }

    /**
     * Admin: Approve leave from website
     */
    @PostMapping("/admin/approve/{leaveId}")
    public ResponseEntity<Map<String, Object>> approveLeaveFromInbox(
            @PathVariable("leaveId") Long leaveId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) Map<String, String> body) {
        User user = (User) userDetails;
        String comments = body != null ? body.get("comments") : null;
        try {
            LeaveRequestDTO dto = leaveService.approveLeaveById(leaveId, user.getId(), comments);
            return ResponseEntity.ok(Map.of(
                    "message", "Leave request approved successfully",
                    "leave", dto
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Admin: Reject leave from website
     */
    @PostMapping("/admin/reject/{leaveId}")
    public ResponseEntity<Map<String, Object>> rejectLeaveFromInbox(
            @PathVariable("leaveId") Long leaveId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) Map<String, String> body) {
        User user = (User) userDetails;
        String comments = body != null ? body.get("comments") : null;
        try {
            LeaveRequestDTO dto = leaveService.rejectLeaveById(leaveId, user.getId(), comments);
            return ResponseEntity.ok(Map.of(
                    "message", "Leave request rejected",
                    "leave", dto
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
