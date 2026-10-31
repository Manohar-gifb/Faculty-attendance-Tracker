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
public class AuthResponse {

    private String token;
    @Builder.Default
    private String tokenType = "Bearer";
    private Long userId;
    private String email;
    private String fullName;
    private String firstName;
    private String lastName;
    private Role role;
    private String departmentName;
    private Long departmentId;
    private String employeeId;
    private String phone;
    private String profilePictureUrl;
    private boolean firstLogin;
}
