package com.college.attendance.dto;

import com.college.attendance.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMemberDTO {

    private Long id;
    private String employeeId;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String phone;
    private Role role;
    private String departmentName;
    private String departmentCode;
    private String profilePictureUrl;
    private Boolean isActive;
    private Boolean isClockedIn;
}
