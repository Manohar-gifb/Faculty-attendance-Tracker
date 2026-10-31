package com.college.attendance.dto;

import com.college.attendance.enums.Role;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCreateRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    // Password is optional — user sets it via setup link sent to email
    private String password;

    @NotNull(message = "Role is required")
    private Role role;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Long departmentId;

    private String employeeId;

    private String phone;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Long leaveApproverId;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Long secondaryApproverId;

    /**
     * Faculty to place under the new HOD/TPO/Principal
     * (Faculty → approver mapping offered by the create-account form).
     */
    private List<Long> mappedFacultyIds;
}
