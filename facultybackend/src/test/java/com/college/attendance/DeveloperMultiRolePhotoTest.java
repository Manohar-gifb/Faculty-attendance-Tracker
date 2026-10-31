package com.college.attendance;

import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.support.StubEmailConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-role + webcam-photo coverage for Developer account creation:
 * accounts can be created with a profile photo for every role
 * (FACULTY, HOD, TPO, PRINCIPAL, VC), the photo is stored under
 * /uploads/profile-pictures/ and linked to exactly the created user,
 * the photo size/type validation is enforced, and non-DEVELOPER users
 * cannot create accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class DeveloperMultiRolePhotoTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StubEmailConfiguration.StubEmailService emailService;

    private String developerToken;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        StubEmailConfiguration.StubEmailService.reset();
        developerToken = login("admin@test.local", "Dev@12345");
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private Department createDepartment(String code, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/developer/departments")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "code", code,
                                "description", "multi-role photo test"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        return departmentRepository.findById(id).orElseThrow();
    }

    /** Creates a verified, active non-developer user directly in the DB. */
    private User createStaffUser(String email, Role role, Department department) {
        return userRepository.save(User.builder()
                .employeeId(role.name().substring(0, 3) + suffix + UUID.randomUUID().toString().substring(0, 4))
                .firstName("Staff")
                .lastName("Member")
                .email(email)
                .passwordHash(passwordEncoder.encode("Staff@12345"))
                .role(role)
                .department(department)
                .isActive(true)
                .firstLogin(false)
                .build());
    }

    private String tokenFor(User user) throws Exception {
        return login(user.getEmail(), "Staff@12345");
    }

    private MockMultipartFile photo(String contentType, byte[] bytes) {
        return new MockMultipartFile("photo", "photo.jpg", contentType, bytes);
    }

    /**
     * Payload starting with the real JPEG magic bytes (FF D8 FF) — the backend
     * now validates uploaded image CONTENT, so stub payloads must look like
     * actual JPEG files.
     */
    private static byte[] jpegPayload(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    private Map<String, Object> accountPayload(String email, Role role, Department department) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("firstName", "Multi");
        payload.put("lastName", "Role");
        payload.put("email", email);
        payload.put("role", role.name());
        if (department != null) {
            payload.put("departmentId", department.getId());
        }
        return payload;
    }

    private String createAccountWithPhotoEmail(Role role, Department department) throws Exception {
        String email = role.name().toLowerCase() + ".photo." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegPayload(20_000)))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(email, role, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true))
                .andExpect(jsonPath("$.activationStatus").value("PENDING"))
                .andExpect(jsonPath("$.userId").exists());

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.getProfilePictureUrl()).isNotBlank();
        assertThat(user.getProfilePictureUrl()).startsWith("/uploads/profile-pictures/");

        return email;
    }

    // =========================================================
    // One account per role, each created together with a webcam photo
    // =========================================================

    @Test
    @DisplayName("FACULTY account can be created with a webcam photo")
    void facultyAccountCanBeCreatedWithPhoto() throws Exception {
        Department department = createDepartment("MRF" + suffixShort(), "MultiRole Faculty Dept " + suffix);
        createAccountWithPhotoEmail(Role.FACULTY, department);
    }

    @Test
    @DisplayName("HOD account can be created with a webcam photo")
    void hodAccountCanBeCreatedWithPhoto() throws Exception {
        Department department = createDepartment("MRH" + suffixShort(), "MultiRole HOD Dept " + suffix);
        createAccountWithPhotoEmail(Role.HOD, department);
    }

    @Test
    @DisplayName("TPO account can be created with a webcam photo")
    void tpoAccountCanBeCreatedWithPhoto() throws Exception {
        Department department = createDepartment("MRT" + suffixShort(), "MultiRole TPO Dept " + suffix);
        createAccountWithPhotoEmail(Role.TPO, department);
    }

    @Test
    @DisplayName("PRINCIPAL account can be created with a webcam photo")
    void principalAccountCanBeCreatedWithPhoto() throws Exception {
        Department department = createDepartment("MRP" + suffixShort(), "MultiRole Principal Dept " + suffix);
        createAccountWithPhotoEmail(Role.PRINCIPAL, department);
    }

    @Test
    @DisplayName("VC account can be created with a webcam photo")
    void vcAccountCanBeCreatedWithPhoto() throws Exception {
        Department department = createDepartment("MRV" + suffixShort(), "MultiRole VC Dept " + suffix);
        createAccountWithPhotoEmail(Role.VC, department);
    }

    // =========================================================
    // Photo is linked to exactly the created user, never shared
    // =========================================================

    @Test
    @DisplayName("Photo is associated with the created user id and never shared across accounts")
    void photoIsAssociatedWithExactlyOneUser() throws Exception {
        Department department = createDepartment("MRX" + suffixShort(), "MultiRole Mapping Dept " + suffix);

        MvcResult first = mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegPayload(25_000)))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(
                                        "first.map." + suffix + "." + UUID.randomUUID() + "@test.local",
                                        Role.VC, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andReturn();
        long firstUserId = objectMapper.readTree(first.getResponse().getContentAsString()).get("userId").asLong();
        User firstUser = userRepository.findById(firstUserId).orElseThrow();
        assertThat(firstUser.getProfilePictureUrl()).isNotBlank();
        assertThat(firstUser.getProfilePictureUrl())
                .contains("user_" + firstUserId + "_");

        // A second account (different role) must not inherit the first photo.
        MvcResult second = mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegPayload(15_000)))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(
                                        "second.map." + suffix + "." + UUID.randomUUID() + "@test.local",
                                        Role.PRINCIPAL, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andReturn();
        long secondUserId = objectMapper.readTree(second.getResponse().getContentAsString()).get("userId").asLong();
        User secondUser = userRepository.findById(secondUserId).orElseThrow();

        assertThat(secondUser.getProfilePictureUrl()).isNotBlank();
        assertThat(secondUser.getProfilePictureUrl()).isNotEqualTo(firstUser.getProfilePictureUrl());
        assertThat(secondUser.getProfilePictureUrl())
                .contains("user_" + secondUserId + "_");
    }

    // =========================================================
    // Account creation without a photo keeps existing behaviour
    // =========================================================

    @Test
    @DisplayName("Account creation without a photo leaves profilePictureUrl empty")
    void accountWithoutPhotoHasNoProfilePicture() throws Exception {
        Department department = createDepartment("MRN" + suffixShort(), "MultiRole NoPhoto Dept " + suffix);
        String email = "nophoto." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accountPayload(email, Role.HOD, department))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getProfilePictureUrl()).isNull();
    }

    // =========================================================
    // Photo validation (mirrors saveProfilePhoto rules)
    // =========================================================

    @Test
    @DisplayName("Invalid photo does not block account creation for non-faculty - photo is skipped")
    void nonImageUploadIsSkipped() throws Exception {
        Department department = createDepartment("MRI" + suffixShort(), "MultiRole Invalid Dept " + suffix);
        String email = "bad.image." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "payload.bin", "application/octet-stream",
                                "definitely not an image".getBytes()))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(email, Role.HOD, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        // the account is still created; only the photo was skipped
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getProfilePictureUrl()).isNull();
    }

    @Test
    @DisplayName("Oversized image (>5MB) is skipped for non-faculty without blocking account creation")
    void oversizedImageIsSkipped() throws Exception {
        Department department = createDepartment("MRO" + suffixShort(), "MultiRole Oversized Dept " + suffix);
        String email = "big.image." + suffix + "." + UUID.randomUUID() + "@test.local";

        byte[] big = new byte[6 * 1024 * 1024]; // 6MB > 5MB limit
        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", big))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(email, Role.HOD, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true));

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getProfilePictureUrl()).isNull();
    }

    @Test
    @DisplayName("Creating FACULTY account without face photo is rejected with 400 Bad Request")
    void facultyAccountRequiresFacePhoto() throws Exception {
        Department department = createDepartment("MRF" + suffixShort(), "MultiRole ReqFace Dept " + suffix);
        String email = "faculty.noface." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(email, Role.FACULTY, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Face capture is mandatory")));

        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    @DisplayName("Creating FACULTY account with invalid photo content is rejected with 400 Bad Request")
    void facultyAccountWithInvalidPhotoFails() throws Exception {
        Department department = createDepartment("MRF" + suffixShort(), "MultiRole BadFace Dept " + suffix);
        String email = "faculty.badface." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "payload.bin", "application/octet-stream",
                                "not-a-real-face-image".getBytes()))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(email, Role.FACULTY, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    // =========================================================
    // Authorization: only DEVELOPER can create accounts
    // =========================================================

    @Test
    @DisplayName("FACULTY user cannot create an account (403)")
    void facultyCannotCreateAccounts() throws Exception {
        Department department = createDepartment("MRA" + suffixShort(), "MultiRole Auth Dept " + suffix);
        User faculty = createStaffUser("auth.faculty." + suffix + "." + UUID.randomUUID() + "@test.local",
                Role.FACULTY, department);

        String token = tokenFor(faculty);
        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", new byte[10_000]))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(
                                        "hacked.principal." + suffix + "." + UUID.randomUUID() + "@test.local",
                                        Role.PRINCIPAL, department))))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HOD user cannot create an account (403)")
    void hodCannotCreateAccounts() throws Exception {
        Department department = createDepartment("MRB" + suffixShort(), "MultiRole AuthB Dept " + suffix);
        User hod = createStaffUser("auth.hod." + suffix + "." + UUID.randomUUID() + "@test.local",
                Role.HOD, department);

        String token = tokenFor(hod);
        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", new byte[10_000]))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(
                                        "hacked.vc." + suffix + "." + UUID.randomUUID() + "@test.local",
                                        Role.VC, department))))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request to create an account is refused")
    void anonymousCannotCreateAccounts() throws Exception {
        Department department = createDepartment("MRC" + suffixShort(), "MultiRole AuthC Dept " + suffix);

        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", new byte[10_000]))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(
                                        "hacked.anon." + suffix + "." + UUID.randomUUID() + "@test.local",
                                        Role.VC, department)))))
                .andExpect(status().is4xxClientError());
    }

    // =========================================================
    // Mandatory Faculty Photo & Edit Account Photo Upload Tests
    // =========================================================

    @Test
    @DisplayName("Creating FACULTY account without photo via JSON is rejected (400)")
    void facultyAccountWithoutPhotoViaJsonIsRejected() throws Exception {
        Department department = createDepartment("MFA" + suffixShort(), "Mandatory Faculty Dept " + suffix);
        String email = "faculty.nophoto." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accountPayload(email, Role.FACULTY, department))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Profile photo is required")));
    }

    @Test
    @DisplayName("Creating FACULTY account without photo via multipart is rejected (400)")
    void facultyAccountWithoutPhotoViaMultipartIsRejected() throws Exception {
        Department department = createDepartment("MFB" + suffixShort(), "Mandatory Faculty Dept B " + suffix);
        String email = "faculty.nophoto2." + suffix + "." + UUID.randomUUID() + "@test.local";

        mockMvc.perform(multipart("/developer/accounts")
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(accountPayload(email, Role.FACULTY, department))))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Profile photo is required")));
    }

    @Test
    @DisplayName("Existing account without photo can receive one through Edit Account photo upload")
    void existingAccountCanReceivePhotoViaEdit() throws Exception {
        Department department = createDepartment("MFE" + suffixShort(), "Edit Photo Dept " + suffix);
        User staff = createStaffUser("staff.nophoto." + suffix + "." + UUID.randomUUID() + "@test.local", Role.FACULTY, department);
        assertThat(staff.getProfilePictureUrl()).isNull();

        mockMvc.perform(multipart("/developer/accounts/" + staff.getId() + "/photo")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegPayload(20_000)))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Profile photo updated successfully"))
                .andExpect(jsonPath("$.account.profilePictureUrl").isString());

        User updated = userRepository.findById(staff.getId()).orElseThrow();
        assertThat(updated.getProfilePictureUrl()).isNotBlank();
        assertThat(updated.getProfilePictureUrl()).startsWith("/uploads/profile-pictures/user_" + staff.getId() + "_");
    }

    @Test
    @DisplayName("Existing photo can be replaced by Developer through Edit Account")
    void existingPhotoCanBeReplacedByDeveloper() throws Exception {
        Department department = createDepartment("MFR" + suffixShort(), "Replace Photo Dept " + suffix);
        String email = createAccountWithPhotoEmail(Role.HOD, department);
        User user = userRepository.findByEmail(email).orElseThrow();
        String initialPhotoUrl = user.getProfilePictureUrl();
        assertThat(initialPhotoUrl).isNotBlank();

        mockMvc.perform(multipart("/developer/accounts/" + user.getId() + "/photo")
                        .file(new MockMultipartFile("photo", "new_photo.jpg", "image/jpeg", jpegPayload(25_000)))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Profile photo updated successfully"))
                .andExpect(jsonPath("$.account.profilePictureUrl").isString());

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getProfilePictureUrl()).isNotBlank();
        assertThat(updated.getProfilePictureUrl()).isNotEqualTo(initialPhotoUrl);
        assertThat(updated.getProfilePictureUrl()).startsWith("/uploads/profile-pictures/user_" + user.getId() + "_");
    }

    @Test
    @DisplayName("Failed photo upload preserves the previous photo on account")
    void failedUploadPreservesPreviousPhoto() throws Exception {
        Department department = createDepartment("MFP" + suffixShort(), "Preserve Photo Dept " + suffix);
        String email = createAccountWithPhotoEmail(Role.HOD, department);
        User user = userRepository.findByEmail(email).orElseThrow();
        String originalPhotoUrl = user.getProfilePictureUrl();
        assertThat(originalPhotoUrl).isNotBlank();

        // Attempt upload with invalid content (not an image)
        mockMvc.perform(multipart("/developer/accounts/" + user.getId() + "/photo")
                        .file(new MockMultipartFile("photo", "corrupt.bin", "application/octet-stream", "bad data".getBytes()))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest());

        // Attempt upload with empty photo
        mockMvc.perform(multipart("/developer/accounts/" + user.getId() + "/photo")
                        .file(new MockMultipartFile("photo", "empty.jpg", "image/jpeg", new byte[0]))
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isBadRequest());

        // Verify the original photo URL was completely preserved
        User afterFailedAttempts = userRepository.findById(user.getId()).orElseThrow();
        assertThat(afterFailedAttempts.getProfilePictureUrl()).isEqualTo(originalPhotoUrl);
    }

    @Test
    @DisplayName("Non-developer user cannot update account photo (403 Forbidden)")
    void nonDeveloperCannotUpdateAccountPhoto() throws Exception {
        Department department = createDepartment("MFX" + suffixShort(), "Forbidden Photo Dept " + suffix);
        User staff = createStaffUser("staff.viewer." + suffix + "." + UUID.randomUUID() + "@test.local", Role.FACULTY, department);
        User target = createStaffUser("staff.target." + suffix + "." + UUID.randomUUID() + "@test.local", Role.FACULTY, department);

        String staffToken = tokenFor(staff);
        mockMvc.perform(multipart("/developer/accounts/" + target.getId() + "/photo")
                        .file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegPayload(15_000)))
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================
    // Helpers
    // =========================================================

    private String suffixShort() {
        return suffix.substring(0, 6).toUpperCase();
    }
}
