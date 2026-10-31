package com.college.attendance.enums;

/**
 * Purpose of the single-use token currently stored (hashed) for a user.
 *
 * The two workflows are kept strictly separate:
 *  - ACTIVATION     : issued when the Developer/Super Admin creates an account.
 *                     Only a PENDING_ACTIVATION account can consume it, and using
 *                     it activates the account.
 *  - PASSWORD_RESET : issued for an already ACTIVE account (forgot password /
 *                     admin-initiated reset). It can never activate an account.
 */
public enum TokenPurpose {
    ACTIVATION,
    PASSWORD_RESET
}
