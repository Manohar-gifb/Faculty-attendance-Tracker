package com.college.attendance.exception;

/**
 * Thrown when a second attendance record is attempted for the same user and
 * the same day (unique constraint {@code uk_attendance_user_date}), typically
 * the loser of a concurrent punch-in race. Mapped by GlobalExceptionHandler
 * to HTTP 409 Conflict with a clean, user-facing message.
 */
public class DuplicateAttendanceException extends RuntimeException {

    public DuplicateAttendanceException(String message) {
        super(message);
    }
}
