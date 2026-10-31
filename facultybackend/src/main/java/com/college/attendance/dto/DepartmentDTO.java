package com.college.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentDTO {
    private Long id;
    private String name;
    private String code;
    private String description;
    private Boolean isActive;
    private Long facultyCount;
    private Long totalUsers;
    private LocalDateTime createdAt;
}
