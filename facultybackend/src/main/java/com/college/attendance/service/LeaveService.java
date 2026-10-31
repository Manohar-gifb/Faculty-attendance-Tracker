package com.college.attendance.service;

import com.college.attendance.dto.LeaveRequestDTO;
import com.college.attendance.entity.LeaveRequest;
import com.college.attendance.entity.User;
import com.college.attendance.enums.LeaveStatus;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.LeaveRequestRepository;
import com.college.attendance.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.leave.token.expiry-hours:72}")
    private int tokenExpiryHours;

    @Value("${app.leave.quotas.casual:12}")
    private int casualQuota;

    @Value("${app.leave.quotas.sick:10}")
    private int sickQuota;

    @Value("${app.leave.quotas.earned:15}")
    private int earnedQuota;

    /**
     * Annual entitlement for a quota-tracked leave type, or {@code null} when
     * the type has no capped annual balance (MEDICAL, OD, MATERNITY, PATERNITY).
     * Mirrors the quotas displayed on the Leave Balance page (Casual 12,
     * Sick 10, Earned 15) and is configurable via app.leave.quotas.*.
     */
    private Integer annualQuotaFor(com.college.attendance.enums.LeaveType type) {
        if (type == null) return null;
        return switch (type) {
            case CASUAL -> casualQuota;
            case SICK -> sickQuota;
            case EARNED -> earnedQuota;
            default -> null;
        };
    }

    /** Inclusive calendar-day count of a leave span (matches getDurationDays and the frontend). */
    private int leaveSpanDays(java.time.LocalDate start, java.time.LocalDate end) {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * Apply for leave
     */
    @Transactional
    public LeaveRequestDTO applyLeave(Long userId, LeaveRequestDTO request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.getLeaveType() == null) {
            throw new RuntimeException("Leave type is required");
        }
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new RuntimeException("Start date and end date are required");
        }
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new RuntimeException("Start date cannot be after end date");
        }

        // Check for overlapping leaves
        List<LeaveRequest> overlapping = leaveRequestRepository.findOverlappingLeaves(
                userId, request.getStartDate(), request.getEndDate());
        if (!overlapping.isEmpty()) {
            throw new RuntimeException("You already have a leave request for the selected dates");
        }

        // Server-side annual balance enforcement. Only APPROVED leaves consume
        // the quota, exactly matching the balance math on the Leave Balance page;
        // the approver is the final gate for pending requests.
        Integer quota = annualQuotaFor(request.getLeaveType());
        if (quota != null) {
            int requestedDays = leaveSpanDays(request.getStartDate(), request.getEndDate());
            int usedDays = leaveRequestRepository
                    .findByUserIdAndStatusOrderByCreatedAtDesc(userId, LeaveStatus.APPROVED).stream()
                    .filter(lr -> lr.getLeaveType() == request.getLeaveType())
                    .mapToInt(lr -> leaveSpanDays(lr.getStartDate(), lr.getEndDate()))
                    .sum();
            int remaining = Math.max(quota - usedDays, 0);
            if (requestedDays > remaining) {
                log.warn("Leave quota exceeded for user {} ({}): requested {} days, remaining {} of {}",
                        user.getEmail(), request.getLeaveType(), requestedDays, remaining, quota);
                throw new RuntimeException(
                        "Insufficient " + request.getLeaveType() + " leave balance: you are requesting "
                                + requestedDays + " day(s), but only " + remaining + " of the annual "
                                + quota + "-day quota remains (" + usedDays + " day(s) already approved).");
            }
        }

        // Generate approval token
        String approvalToken = UUID.randomUUID().toString();
        LocalDateTime tokenExpiry = LocalDateTime.now().plusHours(tokenExpiryHours);

        LeaveRequest leaveRequest = LeaveRequest.builder()
                .user(user)
                .leaveType(request.getLeaveType())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .reason(request.getReason())
                .attachmentUrl(request.getAttachmentUrl())
                .approvalToken(approvalToken)
                .approvalTokenExpiry(tokenExpiry)
                .build();

        leaveRequest = leaveRequestRepository.save(leaveRequest);
        log.info("Leave request #{} created for user {}", leaveRequest.getId(), user.getEmail());

        // No email sent — leave approval is handled in-app via Inbox

        return mapToDTO(leaveRequest);
    }

    /**
     * @deprecated Email-based leave approvals are decommissioned. Leave reviews must occur via the website.
     */
    @Deprecated
    @Transactional
    public LeaveRequestDTO approveLeave(String token, String comments) {
        throw new UnsupportedOperationException("Email-based leave approvals are decommissioned. Please review via the website Leave Management portal.");
    }

    /**
     * @deprecated Email-based leave rejections are decommissioned. Leave reviews must occur via the website.
     */
    @Deprecated
    @Transactional
    public LeaveRequestDTO rejectLeave(String token, String comments) {
        throw new UnsupportedOperationException("Email-based leave rejections are decommissioned. Please review via the website Leave Management portal.");
    }

    /**
     * Admin: Approve leave by ID (from website Inbox)
     */
    @Transactional
    public LeaveRequestDTO approveLeaveById(Long leaveId, Long approverId, String comments) {
        LeaveRequest leaveRequest = leaveRequestRepository.findById(leaveId)
                .orElseThrow(() -> new RuntimeException("Leave request not found"));

        if (leaveRequest.getStatus() != LeaveStatus.PENDING) {
            throw new RuntimeException("Leave request has already been " + leaveRequest.getStatus());
        }

        User approver = userRepository.findById(approverId)
                .orElseThrow(() -> new RuntimeException("Approver not found"));

        validateApproverAuthorization(leaveRequest, approver);

        leaveRequest.setStatus(LeaveStatus.APPROVED);
        leaveRequest.setApprovedBy(approver);
        leaveRequest.setReviewedAt(LocalDateTime.now());
        leaveRequest.setReviewerComments(comments);
        leaveRequest = leaveRequestRepository.save(leaveRequest);

        log.info("Leave request {} approved by {} (admin)", leaveId, approver.getEmail());
        return mapToDTO(leaveRequest);
    }

    /**
     * Admin: Reject leave by ID (from website Inbox)
     */
    @Transactional
    public LeaveRequestDTO rejectLeaveById(Long leaveId, Long approverId, String comments) {
        LeaveRequest leaveRequest = leaveRequestRepository.findById(leaveId)
                .orElseThrow(() -> new RuntimeException("Leave request not found"));

        if (leaveRequest.getStatus() != LeaveStatus.PENDING) {
            throw new RuntimeException("Leave request has already been " + leaveRequest.getStatus());
        }

        User approver = userRepository.findById(approverId)
                .orElseThrow(() -> new RuntimeException("Approver not found"));

        validateApproverAuthorization(leaveRequest, approver);

        leaveRequest.setStatus(LeaveStatus.REJECTED);
        leaveRequest.setApprovedBy(approver);
        leaveRequest.setReviewedAt(LocalDateTime.now());
        leaveRequest.setReviewerComments(comments);
        leaveRequest = leaveRequestRepository.save(leaveRequest);

        log.info("Leave request {} rejected by {} (admin)", leaveId, approver.getEmail());
        return mapToDTO(leaveRequest);
    }

    private void validateApproverAuthorization(LeaveRequest leaveRequest, User approver) {
        if (leaveRequest.getUser().getId().equals(approver.getId())) {
            throw new RuntimeException("You cannot approve or reject your own leave request");
        }

        Role role = approver.getRole();
        if (role == Role.DEVELOPER || role == Role.VC || role == Role.PRINCIPAL) {
            return;
        }

        User faculty = leaveRequest.getUser();
        boolean isExplicitApprover = (faculty.getLeaveApprover() != null && faculty.getLeaveApprover().getId().equals(approver.getId()))
                || (faculty.getSecondaryApprover() != null && faculty.getSecondaryApprover().getId().equals(approver.getId()));
        if (isExplicitApprover) {
            return;
        }

        // HOD can review leaves of faculty in their own department
        if (role == Role.HOD && approver.getDepartment() != null
                && faculty.getDepartment() != null
                && approver.getDepartment().getId().equals(faculty.getDepartment().getId())) {
            return;
        }

        throw new RuntimeException("You are not authorized to review leave requests for this faculty member");
    }

    /**
     * Admin: Get pending leaves mapped to this approver
     * - HOD/TPO: sees pending leaves from faculty who have them as leaveApprover
     * - Principal/VC: sees pending leaves from HODs/TPOs who have them as leaveApprover
     * - Developer: sees all pending leaves
     */
    public List<LeaveRequestDTO> getPendingLeavesForApprover(Long approverId) {
        User approver = userRepository.findById(approverId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Regular faculty users cannot view colleagues' pending leaves
        if (approver.getRole() == Role.FACULTY) {
            return List.of();
        }

        // Developer sees all pending leaves
        if (approver.getRole() == Role.DEVELOPER) {
            return leaveRequestRepository.findByStatusOrderByCreatedAtDesc(LeaveStatus.PENDING)
                    .stream().map(this::mapToDTO).collect(Collectors.toList());
        }

        // Find all users who have this approver as primary/secondary approver, OR belong to the approver's department
        Set<Long> candidateUserIds = new java.util.LinkedHashSet<>();
        userRepository.findByLeaveApprover(approver).forEach(u -> candidateUserIds.add(u.getId()));
        userRepository.findBySecondaryApprover(approver).forEach(u -> candidateUserIds.add(u.getId()));

        if (approver.getRole() != Role.FACULTY && approver.getDepartment() != null) {
            userRepository.findByDepartmentId(approver.getDepartment().getId()).stream()
                    .filter(u -> u.getRole() == Role.FACULTY)
                    .forEach(u -> candidateUserIds.add(u.getId()));
        }

        // Never include the approver themselves
        candidateUserIds.remove(approver.getId());

        if (candidateUserIds.isEmpty()) {
            return List.of();
        }

        // Return pending leaves from mapped users
        return leaveRequestRepository.findByUserIdInAndStatus(new java.util.ArrayList<>(candidateUserIds), LeaveStatus.PENDING)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Get user's leave requests
     */
    public List<LeaveRequestDTO> getUserLeaves(Long userId) {
        return leaveRequestRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Get pending leave requests for admin review
     */
    public List<LeaveRequestDTO> getPendingLeaves(Long departmentId) {
        if (departmentId != null) {
            return leaveRequestRepository.findByDepartmentIdAndStatus(departmentId, LeaveStatus.PENDING)
                    .stream().map(this::mapToDTO).collect(Collectors.toList());
        }
        return leaveRequestRepository.findByStatusOrderByCreatedAtDesc(LeaveStatus.PENDING)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Get all leaves for a department
     */
    public List<LeaveRequestDTO> getDepartmentLeaves(Long departmentId) {
        return leaveRequestRepository.findByDepartmentIdOrderByCreatedAtDesc(departmentId)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Cancel a pending leave request
     */
    @Transactional
    public LeaveRequestDTO cancelLeave(Long userId, Long leaveId) {
        LeaveRequest leaveRequest = leaveRequestRepository.findById(leaveId)
                .orElseThrow(() -> new RuntimeException("Leave request not found"));

        if (!leaveRequest.getUser().getId().equals(userId)) {
            throw new RuntimeException("You can only cancel your own leave requests");
        }

        if (leaveRequest.getStatus() != LeaveStatus.PENDING) {
            throw new RuntimeException("Can only cancel pending leave requests");
        }

        leaveRequest.setStatus(LeaveStatus.CANCELLED);
        leaveRequest = leaveRequestRepository.save(leaveRequest);

        log.info("Leave request {} cancelled by user {}", leaveId, userId);
        return mapToDTO(leaveRequest);
    }

    /**
     * Map LeaveRequest entity to DTO
     */
    private LeaveRequestDTO mapToDTO(LeaveRequest lr) {
        return LeaveRequestDTO.builder()
                .id(lr.getId())
                .userId(lr.getUser().getId())
                .facultyName(lr.getUser().getFullName())
                .employeeId(lr.getUser().getEmployeeId())
                .departmentName(lr.getUser().getDepartment() != null ?
                        lr.getUser().getDepartment().getName() : "N/A")
                .leaveType(lr.getLeaveType())
                .startDate(lr.getStartDate())
                .endDate(lr.getEndDate())
                .reason(lr.getReason())
                .attachmentUrl(lr.getAttachmentUrl())
                .status(lr.getStatus())
                .reviewerComments(lr.getReviewerComments())
                .reviewedAt(lr.getReviewedAt())
                .approvedByName(lr.getApprovedBy() != null ? lr.getApprovedBy().getFullName() : null)
                .durationDays(lr.getDurationDays())
                .createdAt(lr.getCreatedAt())
                .build();
    }
}
