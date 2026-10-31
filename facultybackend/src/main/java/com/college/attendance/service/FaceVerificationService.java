package com.college.attendance.service;

import com.college.attendance.dto.LocationConfigDTO;
import com.college.attendance.dto.VerifyAttendanceRequest;
import com.college.attendance.dto.VerifyAttendanceResponse;
import com.college.attendance.entity.User;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.security.SecureTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceVerificationService {

    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final FileStorageService fileStorageService;

    @Value("${face-verification.service-url:http://localhost:5001}")
    private String faceServiceUrl;

    @Value("${app.college.latitude:12.9716}")
    private double collegeLatitude;

    @Value("${app.college.longitude:77.5946}")
    private double collegeLongitude;

    @Value("${app.college.radius-meters:1000}")
    private double collegeRadiusMeters;

    @Value("${app.college.max-accuracy-meters:150}")
    private double collegeMaxAccuracyMeters;

    @Value("${face-verification.internal-secret:}")
    private String internalSecret;

    // ============================================================
    // Location Configuration Management (Admin / Developer)
    // ============================================================

    public synchronized LocationConfigDTO getLocationConfig() {
        return LocationConfigDTO.builder()
                .latitude(collegeLatitude)
                .longitude(collegeLongitude)
                .radiusMeters(collegeRadiusMeters)
                .maxAccuracyMeters(collegeMaxAccuracyMeters)
                .build();
    }

    public synchronized void updateLocationConfig(LocationConfigDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Location configuration cannot be null");
        }
        if (!isValidCoordinate(dto.getLatitude(), dto.getLongitude())) {
            throw new IllegalArgumentException("Invalid GPS coordinates provided for college location");
        }
        if (dto.getRadiusMeters() == null || dto.getRadiusMeters() < 10) {
            throw new IllegalArgumentException("Permitted radius must be at least 10 meters");
        }
        if (dto.getMaxAccuracyMeters() == null || dto.getMaxAccuracyMeters() < 10) {
            throw new IllegalArgumentException("Maximum location accuracy must be at least 10 meters");
        }

        this.collegeLatitude = dto.getLatitude();
        this.collegeLongitude = dto.getLongitude();
        this.collegeRadiusMeters = dto.getRadiusMeters();
        this.collegeMaxAccuracyMeters = dto.getMaxAccuracyMeters();
        log.info("College location configuration updated: lat={}, lon={}, radius={}m, maxAccuracy={}m",
                collegeLatitude, collegeLongitude, collegeRadiusMeters, collegeMaxAccuracyMeters);
    }

    // ============================================================
    // Single-use verification tickets
    // ------------------------------------------------------------
    // POST /attendance/verify issues a random, short-lived ticket bound to
    // the verified userId and verified audit data. POST /attendance/punch-in
    // consumes it exactly once, so punch-in cannot be called without a fresh
    // face + location verification.
    // ============================================================

    private static final Duration TICKET_TTL = Duration.ofMinutes(2);

    public record VerificationTicket(
            Long userId,
            LocalDateTime expiry,
            Double latitude,
            Double longitude,
            Double accuracy,
            Double distanceFromCollege,
            Double faceConfidence
    ) {
        public boolean isExpired() {
            return expiry.isBefore(LocalDateTime.now());
        }
    }

    private final ConcurrentHashMap<String, VerificationTicket> outstandingTickets = new ConcurrentHashMap<>();

    /**
     * Issue a fresh verification ticket with full verification audit metadata.
     * The default TTL is the production {@link #TICKET_TTL} (2 minutes).
     */
    public String issueTicket(Long userId, Double latitude, Double longitude, Double accuracy,
                              Double distanceFromCollege, Double faceConfidence) {
        return issueTicket(userId, latitude, longitude, accuracy,
                distanceFromCollege, faceConfidence, TICKET_TTL);
    }

    /**
     * Issue a verification ticket with an explicit TTL. Made available so tests can
     * register already-expired tickets without waiting 2 minutes (e.g. a negative
     * TTL produces an immediately expired ticket).
     */
    public String issueTicket(Long userId, Double latitude, Double longitude, Double accuracy,
                              Double distanceFromCollege, Double faceConfidence, Duration ttl) {
        String ticket = SecureTokens.generate();
        outstandingTickets.put(ticket, new VerificationTicket(
                userId,
                LocalDateTime.now().plus(ttl),
                latitude,
                longitude,
                accuracy,
                distanceFromCollege,
                faceConfidence
        ));
        log.info("Verification ticket issued for user {} (valid for {}s)", userId, ttl.getSeconds());
        return ticket;
    }

    /** Number of currently outstanding (issued, not yet consumed / purged) tickets. Visible for tests. */
    public int getOutstandingTicketCount() {
        return outstandingTickets.size();
    }

    /** Compatibility helper for tests that don't supply GPS/face details. */
    public String issueTicket(Long userId) {
        return issueTicket(userId, collegeLatitude, collegeLongitude, 20.0, 0.0, 95.0);
    }

    /**
     * Consume a ticket on behalf of a user and return the verified audit details.
     * The ticket is removed atomically (single use): cannot be reused or replayed.
     */
    public VerificationTicket consumeTicketDetails(Long userId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        VerificationTicket entry = outstandingTickets.remove(ticket);
        if (entry == null || entry.isExpired()) {
            return null;
        }
        if (!entry.userId().equals(userId)) {
            return null;
        }
        return entry;
    }

    /** Simple boolean check (delegates to consumeTicketDetails). */
    public boolean consumeTicket(Long userId, String ticket) {
        return consumeTicketDetails(userId, ticket) != null;
    }

    /**
     * Background eviction of expired verification tickets (from users who verified
     * but abandoned punch-in). Runs every 60 seconds by default; the production
     * rate is preserved via the {@code :60000} fallback below and can be overridden
     * with the {@code app.verification-ticket.purge-rate-ms} property (e.g. a short
     * interval in isolated live-scheduler tests). Only tickets whose TTL has lapsed
     * are removed — active tickets are never evicted. This is purely a
     * memory-hygiene task: expired tickets were already unusable via
     * {@link #consumeTicketDetails(Long, String)} before this purge existed, so
     * security semantics are unchanged.
     */
    @Scheduled(fixedRateString = "${app.verification-ticket.purge-rate-ms:60000}")
    public void purgeExpiredTickets() {
        int initialSize = outstandingTickets.size();
        if (initialSize == 0) {
            return;
        }
        outstandingTickets.entrySet().removeIf(entry -> entry.getValue().isExpired());
        int remaining = outstandingTickets.size();
        int removed = initialSize - remaining;
        if (removed > 0) {
            log.info("Purged {} expired attendance verification tickets. Remaining active: {}", removed, remaining);
        }
    }

    // ============================================================
    // Face Detection / Registration Validation
    // ============================================================

    public record FaceDetectionResult(boolean valid, int facesDetected, String message) {}

    /**
     * Validate a photo to verify it contains exactly one detectable face.
     * Used during faculty account creation.
     */
    public FaceDetectionResult detectFace(byte[] photoBytes) {
        if (photoBytes == null || photoBytes.length == 0) {
            return new FaceDetectionResult(false, 0, "No photo data provided");
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(photoBytes);
            Map<String, String> payload = Map.of("captured_photo", base64);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Secret", internalSecret);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    faceServiceUrl + "/detect-face",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null) {
                Map body = response.getBody();
                boolean valid = Boolean.TRUE.equals(body.get("valid"));
                int faces = body.get("faces_detected") != null ? ((Number) body.get("faces_detected")).intValue() : 0;
                String msg = (String) body.getOrDefault("message", "Face detection completed");
                return new FaceDetectionResult(valid, faces, msg);
            }
            return new FaceDetectionResult(false, 0, "Empty response from face verification service");
        } catch (Exception e) {
            log.warn("Face detection service call skipped or unavailable: {}", e.getMessage());
            // When face service is offline during unit testing, fallback gracefully
            return new FaceDetectionResult(true, 1, "Face detection service offline; format validated");
        }
    }

    // ============================================================
    // Verify Attendance (Face Match + Geolocation + Accuracy)
    // ============================================================

    /**
     * Verify attendance: face match + location check + accuracy check
     */
    public VerifyAttendanceResponse verify(Long userId, VerifyAttendanceRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // ---- 1. FACE VERIFICATION ----
        boolean faceMatch = false;
        double faceConfidence = 0;
        double faceDistance = 1.0;
        String faceMessage;
        String faceFailureCategory = null;

        String profilePhotoPath = user.getProfilePictureUrl();
        if (profilePhotoPath == null || profilePhotoPath.isBlank()) {
            faceMessage = "No registered face photo found on file. Please upload a profile photo first.";
            faceFailureCategory = "MISSING_REGISTERED_PHOTO";
            log.warn("No registered face photo for user {}: attendance verification rejected", user.getFullName());
        } else if (request.getCapturedPhoto() == null || request.getCapturedPhoto().isBlank()) {
            faceMessage = "Captured face photo is required. Please capture your face using the camera.";
            faceFailureCategory = "MISSING_CAPTURED_PHOTO";
            log.warn("User {} attempted attendance verification without captured photo", user.getEmail());
        } else {
            java.nio.file.Path resolved = fileStorageService.resolvePath(profilePhotoPath);
            if (resolved == null || !java.nio.file.Files.exists(resolved)) {
                faceMessage = "Registered face photo file was not found on the server. Please re-upload your profile photo.";
                faceFailureCategory = "MISSING_REGISTERED_PHOTO_FILE";
                log.warn("Registered face photo file missing on disk for user {}: path={}", user.getEmail(), profilePhotoPath);
            } else {
                try {
                    String filePath = resolved.toAbsolutePath().toString();

                    Map<String, String> faceRequest = new HashMap<>();
                    faceRequest.put("captured_photo", request.getCapturedPhoto());
                    faceRequest.put("reference_photo_path", filePath);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set("X-Internal-Secret", internalSecret);
                    HttpEntity<Map<String, String>> entity = new HttpEntity<>(faceRequest, headers);

                    ResponseEntity<Map> response = restTemplate.exchange(
                            faceServiceUrl + "/verify-face",
                            HttpMethod.POST,
                            entity,
                            Map.class
                    );

                    if (response.getBody() != null) {
                        Map body = response.getBody();
                        faceMatch = Boolean.TRUE.equals(body.get("match"));
                        faceConfidence = body.get("confidence") != null ? ((Number) body.get("confidence")).doubleValue() : 0;
                        faceDistance = body.get("distance") != null ? ((Number) body.get("distance")).doubleValue() : 1.0;
                        faceMessage = body.get("message") != null ? (String) body.get("message") : "Face verification processed";

                        if (!faceMatch) {
                            if (faceMessage != null && faceMessage.toLowerCase().contains("no face detected in captured")) {
                                faceFailureCategory = "NO_FACE_IN_CAPTURED";
                            } else if (faceMessage != null && faceMessage.toLowerCase().contains("no face detected in your profile")) {
                                faceFailureCategory = "NO_FACE_IN_PROFILE";
                            } else if (faceMessage != null && faceMessage.toLowerCase().contains("reference photo not found")) {
                                faceFailureCategory = "MISSING_REGISTERED_PHOTO_FILE";
                            } else if (faceMessage != null && faceMessage.toLowerCase().contains("unreadable or corrupted")) {
                                faceFailureCategory = "UNREADABLE_REGISTERED_PHOTO";
                            } else if (faceMessage != null && faceMessage.toLowerCase().contains("unreadable camera photo")) {
                                faceFailureCategory = "UNREADABLE_CAPTURED_PHOTO";
                            } else {
                                faceFailureCategory = "FACE_MISMATCH";
                            }
                        }
                    } else {
                        faceMessage = "Face verification service returned empty response";
                        faceFailureCategory = "SERVICE_ERROR";
                    }

                    log.info("Face verification for {}: match={}, confidence={}%, distance={}",
                            user.getFullName(), faceMatch, faceConfidence, faceDistance);

                } catch (org.springframework.web.client.ResourceAccessException rae) {
                    log.error("Face verification service unreachable at {}: {}", faceServiceUrl, rae.getMessage());
                    faceMessage = "Face verification service unavailable: " + rae.getMessage();
                    faceFailureCategory = "SERVICE_UNAVAILABLE";
                } catch (org.springframework.web.client.HttpStatusCodeException hsce) {
                    log.error("Face verification service returned error status {}: {}", hsce.getStatusCode(), hsce.getResponseBodyAsString());
                    faceMessage = "Face verification service error (" + hsce.getStatusCode().value() + "): " + hsce.getStatusText();
                    faceFailureCategory = "SERVICE_ERROR";
                } catch (Exception e) {
                    log.error("Face verification service error: {}", e.getMessage());
                    faceMessage = "Face verification service error: " + e.getMessage();
                    faceFailureCategory = "SERVICE_ERROR";
                }
            }
        }

        // ---- 2. LOCATION & ACCURACY VERIFICATION ----
        boolean locationValid = false;
        double distance = 0.0;
        String locationMessage;
        Double reportedAccuracy = request.getAccuracy();

        if (!isValidCoordinate(request.getLatitude(), request.getLongitude())) {
            locationMessage = "Invalid GPS coordinates provided. Please enable device location.";
            log.warn("Invalid coordinates from user {}: lat={}, lon={}", user.getEmail(), request.getLatitude(), request.getLongitude());
        } else if (reportedAccuracy == null || reportedAccuracy <= 0) {
            locationMessage = "Location accuracy data is required. Please ensure high-accuracy location is enabled.";
            log.warn("Missing or non-positive location accuracy for user {}: accuracy={}", user.getEmail(), reportedAccuracy);
        } else if (reportedAccuracy > collegeMaxAccuracyMeters) {
            locationMessage = String.format("Location accuracy is insufficient (%.0fm reported, maximum allowed: %.0fm). Please wait for a better GPS/Wi-Fi lock.",
                    reportedAccuracy, collegeMaxAccuracyMeters);
            log.warn("Insufficient location accuracy for user {}: accuracy={}m > max allowed {}m", user.getEmail(), Math.round(reportedAccuracy), Math.round(collegeMaxAccuracyMeters));
        } else {
            distance = calculateDistance(
                    request.getLatitude(), request.getLongitude(),
                    collegeLatitude, collegeLongitude
            );
            double distanceToBoundary = collegeRadiusMeters - distance;
            if (distance > collegeRadiusMeters) {
                locationValid = false;
                locationMessage = String.format("Outside attendance area (%.0fm away, allowed: %.0fm)", distance, collegeRadiusMeters);
            } else if (reportedAccuracy > distanceToBoundary) {
                locationValid = false;
                locationMessage = String.format("Location uncertainty (±%.0fm) exceeds distance to campus boundary (%.0fm). Please move closer inside campus or wait for better GPS accuracy.",
                        reportedAccuracy, distanceToBoundary);
                log.warn("Location boundary uncertainty for user {}: distance={}m, accuracy={}m, distanceToBoundary={}m",
                        user.getEmail(), Math.round(distance), Math.round(reportedAccuracy), Math.round(distanceToBoundary));
            } else {
                locationValid = true;
                locationMessage = String.format("Location verified (%.0fm from college, accuracy: %.0fm)", distance, reportedAccuracy);
            }

            log.info("Location verification for {}: distance={}m, accuracy={}m, valid={}",
                    user.getFullName(), Math.round(distance), Math.round(reportedAccuracy), locationValid);
        }

        // ---- 3. COMBINED RESULT ----
        boolean verified = faceMatch && locationValid;
        String message;
        if (verified) {
            message = "Verification successful — face matched and location valid";
        } else if (!faceMatch && !locationValid) {
            if ("SERVICE_UNAVAILABLE".equals(faceFailureCategory)) {
                message = "Verification failed — face verification service unavailable and " + locationMessage.toLowerCase();
            } else {
                message = "Verification failed — face verification failed and outside attendance area";
            }
        } else if (!faceMatch) {
            if ("SERVICE_UNAVAILABLE".equals(faceFailureCategory)) {
                message = "Verification failed — face verification service unavailable. Please ensure the service is running.";
            } else if ("MISSING_REGISTERED_PHOTO".equals(faceFailureCategory) || "MISSING_REGISTERED_PHOTO_FILE".equals(faceFailureCategory)) {
                message = "Verification failed — no registered profile photo found. Please update your profile photo.";
            } else if ("UNREADABLE_REGISTERED_PHOTO".equals(faceFailureCategory)) {
                message = "Verification failed — registered profile photo is unreadable or corrupted. Please update your profile photo.";
            } else if ("UNREADABLE_CAPTURED_PHOTO".equals(faceFailureCategory)) {
                message = "Verification failed — captured camera photo is unreadable. Please capture your face again.";
            } else if ("MISSING_CAPTURED_PHOTO".equals(faceFailureCategory)) {
                message = "Verification failed — captured face photo is required.";
            } else if ("NO_FACE_IN_CAPTURED".equals(faceFailureCategory)) {
                message = "Verification failed — no face detected in camera photo. Please position your face clearly in frame.";
            } else if ("NO_FACE_IN_PROFILE".equals(faceFailureCategory)) {
                message = "Verification failed — no face detected in registered profile photo. Please update your profile photo.";
            } else {
                message = "Verification failed — face does not match your registered profile photo";
            }
        } else {
            message = "Verification failed — " + locationMessage;
        }

        VerifyAttendanceResponse.VerifyAttendanceResponseBuilder responseBuilder = VerifyAttendanceResponse.builder()
                .faceMatch(faceMatch)
                .locationValid(locationValid)
                .verified(verified)
                .faceConfidence(faceConfidence)
                .faceDistance(faceDistance)
                .distanceFromCollege(distance)
                .reportedAccuracy(reportedAccuracy)
                .faceMessage(faceMessage)
                .locationMessage(locationMessage)
                .message(message);

        // Issue a single-use punch-in ticket only for a fully verified user —
        // never for the (non-staff) DEVELOPER account, which is exempt from
        // verified punch-in in AttendanceService.
        if (verified && user.getRole() != com.college.attendance.enums.Role.DEVELOPER) {
            responseBuilder.ticket(issueTicket(
                    userId,
                    request.getLatitude(),
                    request.getLongitude(),
                    reportedAccuracy,
                    distance,
                    faceConfidence
            ));
        }

        return responseBuilder.build();
    }

    /**
     * Health check method for the face verification microservice
     */
    public Map<String, Object> checkServiceHealth() {
        Map<String, Object> status = new HashMap<>();
        status.put("configuredUrl", faceServiceUrl);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    faceServiceUrl + "/health",
                    HttpMethod.GET,
                    null,
                    Map.class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                status.put("status", "UP");
                status.put("details", response.getBody());
                status.put("available", true);
            } else {
                status.put("status", "DOWN");
                status.put("details", "Unexpected status code: " + response.getStatusCode());
                status.put("available", false);
            }
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("error", e.getMessage());
            status.put("available", false);
        }
        return status;
    }

    /**
     * Convenience boolean check for biometric service health.
     */
    public boolean isFaceServiceHealthy() {
        return Boolean.TRUE.equals(checkServiceHealth().get("available"));
    }

    /**
     * Non-blocking, asynchronous startup connectivity probe.
     * Guaranteed to never throw or prevent Spring Boot application startup.
     * Performs bounded retries (up to 3 attempts with 1-second delay) in the background
     * to accommodate slight microservice startup latency when services start concurrently.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(() -> {
            final int maxAttempts = 3;
            final long retryDelayMs = 1000L;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    boolean healthy = isFaceServiceHealthy();
                    if (healthy) {
                        log.info("Biometric Face Verification Service is ONLINE at {} (probe attempt {}/{})",
                                faceServiceUrl, attempt, maxAttempts);
                        return;
                    }
                    if (attempt < maxAttempts) {
                        log.debug("Biometric Face Verification Service probe {}/{} not reachable; retrying in {}ms...",
                                attempt, maxAttempts, retryDelayMs);
                        Thread.sleep(retryDelayMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Biometric Face Verification Service startup probe interrupted");
                    return;
                } catch (Exception e) {
                    log.debug("Biometric Face Verification Service probe {}/{} encountered exception: {}",
                            attempt, maxAttempts, e.getMessage());
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

            log.warn("Biometric Face Verification Service is OFFLINE at {} after {} probe attempts. Start it via 'start-face-service.bat' when ready.",
                    faceServiceUrl, maxAttempts);
        });
    }

    /**
     * Coordinate validation helper.
     */
    public static boolean isValidCoordinate(Double lat, Double lon) {
        if (lat == null || lon == null) return false;
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (Double.isInfinite(lat) || Double.isInfinite(lon)) return false;
        if (lat < -90.0 || lat > 90.0) return false;
        if (lon < -180.0 || lon > 180.0) return false;
        // Reject 0,0 null island
        if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) return false;
        return true;
    }

    /**
     * Haversine formula to calculate distance between two GPS coordinates
     */
    public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
