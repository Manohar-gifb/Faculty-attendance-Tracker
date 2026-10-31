package com.college.attendance.dto;

import com.college.attendance.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of creating an account: the saved user plus whether the
 * activation/setup email was actually delivered (no fake success).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCreateResult {

    private User user;
    private boolean emailSent;
    private String message;
}
