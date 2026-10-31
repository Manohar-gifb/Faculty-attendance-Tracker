package com.college.attendance.dto;

import com.college.attendance.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileDTO {

    private Long id;
    private String employeeId;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private Role role;
    private Long departmentId;
    private String departmentName;
    private String phone;
    private String address;
    private String bio;
    private String profilePictureUrl;
    private Boolean isActive;
    private Long leaveApproverId;
    private String leaveApproverName;
    private Long secondaryApproverId;
    private String secondaryApproverName;

    /** ACTIVE | INACTIVE | PENDING (created but password not set yet) */
    private String activationStatus;
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;
}
