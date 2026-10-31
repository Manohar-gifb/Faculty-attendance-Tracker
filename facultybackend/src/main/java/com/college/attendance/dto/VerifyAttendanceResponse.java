package com.college.attendance.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VerifyAttendanceResponse {
    private boolean faceMatch;
    private boolean locationValid;
    private boolean verified;
    private double faceConfidence;
    private double faceDistance;
    private double distanceFromCollege;
    private Double reportedAccuracy;
    private String faceMessage;
    private String locationMessage;
    private String message;

    /**
     * Short-lived single-use ticket proving face + location verification
     * passed for this user. Required by POST /attendance/punch-in for all
     * non-DEVELOPER roles; ``null`` when verification failed.
     */
    private String ticket;
}
