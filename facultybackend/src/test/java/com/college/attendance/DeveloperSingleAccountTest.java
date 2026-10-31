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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class DeveloperSingleAccountTest {

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

    private String developerToken;
    private Department testDept;

    @BeforeEach
    void setUp() throws Exception {
        StubEmailConfiguration.StubEmailService.reset();

        testDept = departmentRepository.findByCode("CSE")
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("Computer Science & Engineering")
                        .code("CSE")
                        .isActive(true)
                        .build()));

        // Ensure authorized developer account is present
        List<User> devs = userRepository.findByRole(Role.DEVELOPER);
        User devUser;
        if (devs.isEmpty()) {
            devUser = userRepository.save(User.builder()
                    .employeeId("DEV001")
                    .firstName("System")
                    .lastName("Developer")
                    .email("admin@test.local")
                    .passwordHash(passwordEncoder.encode("Dev@12345"))
                    .role(Role.DEVELOPER)
                    .department(testDept)
                    .isActive(true)
                    .firstLogin(false)
                    .build());
        } else {
            devUser = devs.get(0);
            devUser.setFirstName("System");
            devUser.setLastName("Developer");
            userRepository.save(devUser);
        }

        // Login as developer
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", devUser.getEmail(), "password", "Dev@12345"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        developerToken = node.get("token").asText();
    }

    @Test
    @DisplayName("Developer account has System Developer name and DEVELOPER role")
    void developerAccountDetailsAreCorrect() {
        List<User> developers = userRepository.findByRole(Role.DEVELOPER);
        assertThat(developers).hasSize(1);
        User dev = developers.get(0);
        assertThat(dev.getFirstName()).isEqualTo("System");
        assertThat(dev.getLastName()).isEqualTo("Developer");
        assertThat(dev.getFullName()).isEqualTo("System Developer");
        assertThat(dev.getRole()).isEqualTo(Role.DEVELOPER);
    }

    @Test
    @DisplayName("Attempting to create a second DEVELOPER account via API is rejected")
    void creatingSecondDeveloperAccountIsRejected() throws Exception {
        Map<String, Object> payload = Map.of(
                "firstName", "Second",
                "lastName", "Dev",
                "email", "second.dev@test.local",
                "role", "DEVELOPER"
        );

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only one System Developer account is permitted in the system."));

        assertThat(userRepository.countByRole(Role.DEVELOPER)).isEqualTo(1);
    }

    @Test
    @DisplayName("Attempting to promote an existing non-developer user to DEVELOPER is rejected")
    void promotingUserToDeveloperIsRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User faculty = userRepository.save(User.builder()
                .employeeId("FAC" + suffix)
                .firstName("Regular")
                .lastName("Faculty")
                .email("faculty." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode("Faculty@123"))
                .role(Role.FACULTY)
                .department(testDept)
                .isActive(true)
                .firstLogin(false)
                .build());

        mockMvc.perform(put("/developer/accounts/" + faculty.getId())
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "DEVELOPER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only one System Developer account is permitted in the system."));

        User refreshed = userRepository.findById(faculty.getId()).orElseThrow();
        assertThat(refreshed.getRole()).isEqualTo(Role.FACULTY);
    }

    @Test
    @DisplayName("Normal account creation for other roles (e.g. HOD) continues to work")
    void normalAccountCreationWorks() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload = Map.of(
                "firstName", "New",
                "lastName", "Hod",
                "email", "newhod." + suffix + "@test.local",
                "role", "HOD",
                "departmentId", testDept.getId()
        );

        mockMvc.perform(post("/developer/accounts")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").exists());
    }
}
