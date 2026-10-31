package com.college.attendance.dto;

import lombok.Data;

@Data
public class VerifyAttendanceRequest {
    /** Base64-encoded captured photo (JPEG/PNG) */
    private String capturedPhoto;

    /** User's current latitude */
    private double latitude;

    /** User's current longitude */
    private double longitude;

    /** Device reported GPS/location accuracy in meters */
    private Double accuracy;
}
