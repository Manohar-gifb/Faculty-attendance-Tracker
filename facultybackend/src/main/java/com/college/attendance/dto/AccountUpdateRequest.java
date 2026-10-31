package com.college.attendance.dto;

import com.college.attendance.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountUpdateRequest {

    private String firstName;
    private String lastName;
    private String phone;
    private Role role;
    private Long departmentId;
    private Boolean isActive;
    private Long leaveApproverId;
    private Long secondaryApproverId;
}
