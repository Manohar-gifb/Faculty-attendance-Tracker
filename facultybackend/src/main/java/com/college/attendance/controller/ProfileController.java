package com.college.attendance.controller;

import com.college.attendance.dto.UpdateProfileRequest;
import com.college.attendance.dto.UserProfileDTO;
import com.college.attendance.entity.User;
import com.college.attendance.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import com.college.attendance.util.ImageFileValidator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
@Slf4j
public class ProfileController {

    private final UserRepository userRepository;
    private final com.college.attendance.service.FileStorageService fileStorageService;
    private final com.college.attendance.service.PhotoCleanupService photoCleanupService;

    /**
     * Get current user's profile
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<UserProfileDTO> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        User authUser = (User) userDetails;
        User user = userRepository.findById(authUser.getId()).orElse(authUser);
        UserProfileDTO dto = mapToDTO(user);
        return ResponseEntity.ok(dto);
    }

    /**
     * Update profile (only editable fields: phone, address, bio)
     */
    @PutMapping
    public ResponseEntity<UserProfileDTO> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        User user = (User) userDetails;

        // Only update permitted fields
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        if (request.getAddress() != null) user.setAddress(request.getAddress());
        if (request.getBio() != null) user.setBio(request.getBio());

        user = userRepository.save(user);
        return ResponseEntity.ok(mapToDTO(user));
    }

    /**
     * Upload profile picture for the AUTHENTICATED user only (no user id is
     * ever accepted from the client — users cannot change other users' images).
     * Validation (extension whitelist, MIME type, real image magic bytes,
     * 5 MB limit) is shared with the Developer module via ImageFileValidator.
     */
    @PostMapping("/picture")
    public ResponseEntity<?> uploadProfilePicture(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file) {

        User user = (User) userDetails;

        // Security check: registered faces cannot be overwritten without developer authorization
        if (user.getRole() != com.college.attendance.enums.Role.DEVELOPER
                && user.getProfilePictureUrl() != null && !user.getProfilePictureUrl().isBlank()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(Map.of(
                    "message", "Registered face cannot be overwritten. Contact an administrator for face re-registration."
            ));
        }

        // Captured before repointing: only the developer-overwrite path reaches
        // the prune below (faculty are rejected above).
        final String previousPhotoUrl = user.getProfilePictureUrl();

        final String extension;
        try {
            extension = ImageFileValidator.validatedExtension(file);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }

        // Save with a server-generated UUID filename (no path traversal)
        String filename = ImageFileValidator.safeFilename(extension);
        final String photoUrl;
        try {
            photoUrl = fileStorageService.storeProfilePicture(file, filename);
        } catch (Exception e) {
            log.error("Profile picture storage failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Could not store the image. Please try again."));
        }

        // Associate with the user's account only after file storage is confirmed
        user.setProfilePictureUrl(photoUrl);
        user = userRepository.save(user);

        // Best-effort prune of the superseded photo (developer self-overwrite:
        // this is the only overwrite path here — faculty are rejected above).
        try {
            photoCleanupService.pruneSupersededPhoto(previousPhotoUrl, user.getId());
        } catch (Exception e) {
            log.warn("Superseded profile photo {} left in place (scheduled sweep will reclaim): {}",
                    previousPhotoUrl, e.getMessage());
        }

        return ResponseEntity.ok(mapToDTO(user));
    }

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex) : ".jpg";
    }

    private UserProfileDTO mapToDTO(User user) {
        Long approverId = null;
        String approverName = null;
        try {
            if (user.getLeaveApprover() != null) {
                approverId = user.getLeaveApprover().getId();
                approverName = user.getLeaveApprover().getFullName();
            }
        } catch (Exception ignored) {
            // Lazy proxy fallback
        }

        Long secondaryApproverId = null;
        String secondaryApproverName = null;
        try {
            if (user.getSecondaryApprover() != null) {
                secondaryApproverId = user.getSecondaryApprover().getId();
                secondaryApproverName = user.getSecondaryApprover().getFullName();
            }
        } catch (Exception ignored) {
            // Lazy proxy fallback
        }

        return UserProfileDTO.builder()
                .id(user.getId())
                .employeeId(user.getEmployeeId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole())
                .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
                .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
                .phone(user.getPhone())
                .address(user.getAddress())
                .bio(user.getBio())
                .profilePictureUrl(user.getProfilePictureUrl())
                .isActive(user.getIsActive())
                .leaveApproverId(approverId)
                .leaveApproverName(approverName)
                .secondaryApproverId(secondaryApproverId)
                .secondaryApproverName(secondaryApproverName)
                .build();
    }
}
