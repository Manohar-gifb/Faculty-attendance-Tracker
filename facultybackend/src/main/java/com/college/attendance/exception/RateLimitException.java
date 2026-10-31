package com.college.attendance.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Throttling guard for repeated email-sending actions (activation resend,
 * password-reset requests). Maps to HTTP 429 with a plain user-facing
 * message — no internal details are exposed.
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
