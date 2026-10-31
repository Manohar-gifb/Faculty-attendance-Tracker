package com.college.attendance.config;

import com.college.attendance.exception.DuplicateAttendanceException;
import com.college.attendance.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Unknown API path -> 404 (NoResourceFoundException would otherwise be
     *  caught by the generic handler and reported as 500). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Resource not found");
        body.put("status", HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** Unsupported request Content-Type -> 415 (previously surfaced as 500). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Unsupported content type");
        body.put("status", HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body);
    }

    /** Uploaded file exceeding spring servlet multipart limits -> 413 with a clear message. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected: file exceeds size limit");
        Map<String, Object> body = new HashMap<>();
        body.put("message", "File too large. Maximum size is 5 MB.");
        body.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("message", ex.getMessage());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Duplicate attendance for the same user and day (uk_attendance_user_date) —
     * typically the loser of a concurrent punch-in race -> 409 Conflict with a
     * clean, consistent API error body (never a raw 500 / SQL surface).
     */
    @ExceptionHandler(DuplicateAttendanceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateAttendance(DuplicateAttendanceException ex) {
        log.warn("Duplicate attendance attempt: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("message", ex.getMessage());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Any other database integrity violation -> 409 Conflict with an umbrella
     * message. It never fabricates duplicate-attendance specifics for unrelated
     * constraint violations and never leaks SQL or constraint details.
     * Because DataIntegrityViolationException always aborts the surrounding
     * transaction, the database is left with the winning request's committed
     * state only — no rollback-only or partial state survives the request.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database integrity constraint violation: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Conflicting data: the record already exists or violates a data constraint. Please refresh and try again.");
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("message", ex.getMessage());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Validation failed");
        body.put("errors", errors);
        body.put("status", HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException ex) {
        log.warn("Rate limited: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("message", ex.getMessage());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException ex) {
        log.error("Access denied: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("message", "You do not have permission to perform this action");
        body.put("status", HttpStatus.FORBIDDEN.value());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected exception: {}", ex.getMessage(), ex);
        Map<String, Object> body = new HashMap<>();
        body.put("message", "An unexpected error occurred. Please try again later.");
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
