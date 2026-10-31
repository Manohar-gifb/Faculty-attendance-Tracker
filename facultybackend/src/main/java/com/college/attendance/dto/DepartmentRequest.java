package com.college.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create / update request for a department (Developer module).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentRequest {

    @NotBlank(message = "Department name is required")
    @Size(max = 100, message = "Department name must not exceed 100 characters")
    private String name;

    @NotBlank(message = "Department code is required")
    @Size(max = 20, message = "Department code must not exceed 20 characters")
    @Pattern(regexp = "^[A-Za-z0-9\\-]+$", message = "Department code may only contain letters, numbers and hyphens")
    private String code;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    private Boolean isActive;
}
