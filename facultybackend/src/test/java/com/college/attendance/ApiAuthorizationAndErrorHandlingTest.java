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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for confirmed issues:
 *  1. GET /profile returned 400 (lazy department on the filter-loaded user)
 *  2. Email reject link /leave/reject/{token} returned 403 while approve worked
 *  3. FACULTY tokens could read admin leave/attendance endpoints
 *  4. Unknown API paths and unsupported content types returned 500
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class ApiAuthorizationAndErrorHandlingTest {

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

    private String facultyEmail;
    private final String facultyPassword = "Faculty@123";

    @BeforeEach
    void setUp() {
        StubEmailConfiguration.StubEmailService.reset();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Department department = departmentRepository.findByCode("AHTC" + suffix)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("ApiTest Dept " + suffix)
                        .code("AHTC" + suffix)
                        .isActive(true)
                        .build()));

        facultyEmail = "faculty." + suffix + "@test.local";
        if (userRepository.findByEmail(facultyEmail).isEmpty()) {
            userRepository.save(User.builder()
                    .employeeId("AHTF" + suffix)
                    .firstName("Api")
                    .lastName("Test")
                    .email(facultyEmail)
                    .passwordHash(passwordEncoder.encode(facultyPassword))
                    .role(Role.FACULTY)
                    .department(department)
                    .isActive(true)
                    .firstLogin(false)
                    .build());
        }
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private String facultyToken() throws Exception {
        return login(facultyEmail, facultyPassword);
    }

    // ---------------------------------------------------------------
    // 1. Profile must return data (lazy department loaded with user)
    // ---------------------------------------------------------------
    @Test
    void facultyProfileReturnsData() throws Exception {
        String token = facultyToken();
        mockMvc.perform(get("/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(facultyEmail));
    }

    // ---------------------------------------------------------------
    // 2. Email approve/reject endpoints are decommissioned (require auth / 403)
    // ---------------------------------------------------------------
    @Test
    void emailApproveLinkNotAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/leave/approve/some-invalid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void emailRejectLinkNotAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/leave/reject/some-invalid-token"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------
    // 3. FACULTY must not reach broad admin endpoints, but the
    //    approver-scoped inbox (pending-for-me) stays available and
    //    admin/developer roles keep full access
    // ---------------------------------------------------------------
    @Test
    void facultyCannotListAllPendingLeaves() throws Exception {
        String token = facultyToken();
        mockMvc.perform(get("/leave/admin/pending").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void facultyCannotReadAdminAttendanceStats() throws Exception {
        String token = facultyToken();
        mockMvc.perform(get("/attendance/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void facultyCannotApproveLeaves() throws Exception {
        String token = facultyToken();
        mockMvc.perform(post("/leave/admin/approve/999999")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void facultyInboxPendingForMeStaysAvailable() throws Exception {
        String token = facultyToken();
        mockMvc.perform(get("/leave/admin/pending-for-me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void developerKeepsAccessToAdminPendingLeaves() throws Exception {
        String token = login("admin@test.local", "Dev@12345");
        mockMvc.perform(get("/leave/admin/pending").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void developerKeepsAccessToApproveEndpoint() throws Exception {
        String token = login("admin@test.local", "Dev@12345");
        // passes security (not 403); service then reports the unknown leave id
        mockMvc.perform(post("/leave/admin/approve/999999")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"checked\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // 4. Error handling: unknown path -> 404, wrong content type -> 415
    // ---------------------------------------------------------------
    @Test
    void unknownApiPathReturns404() throws Exception {
        String token = facultyToken();
        mockMvc.perform(get("/no-such-endpoint").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void loginValidationStillReturns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
