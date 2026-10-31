package com.college.attendance.config;

import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.developer.email:admin@college.edu}")
    private String developerEmail;

    @Value("${app.developer.password:}")
    private String developerPassword;

    @Value("${app.developer.employee-id:DEV001}")
    private String developerEmployeeId;

    @Value("${app.admin.email:hod.cse@college.edu}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.employee-id:HOD001}")
    private String adminEmployeeId;

    @Value("${app.faculty.email:faculty@college.edu}")
    private String facultyEmail;

    @Value("${app.faculty.password:}")
    private String facultyPassword;

    @Value("${app.faculty.employee-id:FAC001}")
    private String facultyEmployeeId;

    @Override
    public void run(String... args) {
        seedDepartments();
        seedBaselineAccounts();
        log.info("Data seeding verification completed successfully");
    }

    private void seedDepartments() {
        String[][] standardDepts = {
            {"Computer Science & Engineering", "CSE", "Department of Computer Science and Engineering"},
            {"Information Technology", "IT", "Department of Information Technology"},
            {"Electronics & Communication", "ECE", "Department of Electronics and Communication Engineering"},
            {"Mechanical Engineering", "ME", "Department of Mechanical Engineering"},
            {"Civil Engineering", "CE", "Department of Civil Engineering"}
        };

        for (String[] deptInfo : standardDepts) {
            String name = deptInfo[0];
            String code = deptInfo[1];
            String desc = deptInfo[2];

            if (departmentRepository.findByCode(code).isEmpty()) {
                Department dept = Department.builder()
                        .name(name)
                        .code(code)
                        .description(desc)
                        .isActive(true)
                        .build();
                departmentRepository.save(dept);
                log.info("Department seeded: {} ({})", name, code);
            }
        }
    }

    private void seedBaselineAccounts() {
        Department cse = departmentRepository.findByCode("CSE").orElse(null);

        // 1. Maintain Exactly One Authorized System Developer Account
        List<User> existingDevs = userRepository.findByRole(Role.DEVELOPER);
        if (!existingDevs.isEmpty()) {
            User authorizedDev = existingDevs.stream()
                    .filter(u -> u.getEmail().equalsIgnoreCase(developerEmail))
                    .findFirst()
                    .orElse(existingDevs.get(0));

            if (authorizedDev.getFirstName() == null || authorizedDev.getFirstName().isBlank()) {
                authorizedDev.setFirstName("System");
            }
            if (authorizedDev.getLastName() == null || authorizedDev.getLastName().isBlank()) {
                authorizedDev.setLastName("Developer");
            }
            authorizedDev.setEmail(developerEmail);
            if (authorizedDev.getDepartment() == null && cse != null) {
                authorizedDev.setDepartment(cse);
            }
            if (authorizedDev.getEmployeeId() == null || authorizedDev.getEmployeeId().isBlank()) {
                authorizedDev.setEmployeeId(developerEmployeeId);
            }
            authorizedDev.setIsActive(true);
            userRepository.save(authorizedDev);
            log.info("Authorized System Developer account confirmed: {} <{}>", authorizedDev.getFullName(), authorizedDev.getEmail());

            if (existingDevs.size() > 1) {
                for (User duplicate : existingDevs) {
                    if (!duplicate.getId().equals(authorizedDev.getId())) {
                        log.warn("Consolidating duplicate developer account (id={}, email={}) safely to non-developer role",
                                duplicate.getId(), duplicate.getEmail());
                        duplicate.setRole(Role.FACULTY);
                        duplicate.setIsActive(false);
                        userRepository.save(duplicate);
                    }
                }
            }
        } else {
            Optional<User> byEmail = userRepository.findByEmail(developerEmail);
            if (byEmail.isPresent()) {
                User dev = byEmail.get();
                if (dev.getFirstName() == null || dev.getFirstName().isBlank()) {
                    dev.setFirstName("System");
                }
                if (dev.getLastName() == null || dev.getLastName().isBlank()) {
                    dev.setLastName("Developer");
                }
                dev.setRole(Role.DEVELOPER);
                dev.setIsActive(true);
                userRepository.save(dev);
                log.info("Existing account updated to sole System Developer: {}", developerEmail);
            } else {
                String effectiveDevPassword = (developerPassword != null && !developerPassword.isBlank())
                        ? developerPassword
                        : "DevSeed#" + java.util.UUID.randomUUID().toString().substring(0, 8);

                User developer = User.builder()
                        .employeeId(developerEmployeeId)
                        .firstName("System")
                        .lastName("Developer")
                        .email(developerEmail)
                        .passwordHash(passwordEncoder.encode(effectiveDevPassword))
                        .role(Role.DEVELOPER)
                        .department(cse)
                        .isActive(true)
                        .firstLogin(false)
                        .build();

                userRepository.save(developer);
                log.info("Sole System Developer account seeded: {} <{}>", developer.getFullName(), developerEmail);
            }
        }

        // 2. Baseline Admin (HOD) Account
        User adminUser = null;
        Optional<User> existingAdmin = userRepository.findByEmail(adminEmail);
        if (existingAdmin.isEmpty()) {
            String effectiveAdminPassword = (adminPassword != null && !adminPassword.isBlank())
                    ? adminPassword
                    : "AdminSeed#" + java.util.UUID.randomUUID().toString().substring(0, 8);

            adminUser = User.builder()
                    .employeeId(adminEmployeeId)
                    .firstName("Rajesh")
                    .lastName("Kumar")
                    .email(adminEmail)
                    .passwordHash(passwordEncoder.encode(effectiveAdminPassword))
                    .role(Role.HOD)
                    .department(cse)
                    .phone("555-0100")
                    .isActive(true)
                    .firstLogin(false)
                    .build();

            adminUser = userRepository.save(adminUser);
            log.info("Baseline Admin/HOD account seeded: {}", adminEmail);
        } else {
            adminUser = existingAdmin.get();
        }

        // 3. Baseline Faculty Account
        if (userRepository.findByEmail(facultyEmail).isEmpty()) {
            String effectiveFacultyPassword = (facultyPassword != null && !facultyPassword.isBlank())
                    ? facultyPassword
                    : "FacultySeed#" + java.util.UUID.randomUUID().toString().substring(0, 8);

            User faculty = User.builder()
                    .employeeId(facultyEmployeeId)
                    .firstName("Amit")
                    .lastName("Patel")
                    .email(facultyEmail)
                    .passwordHash(passwordEncoder.encode(effectiveFacultyPassword))
                    .role(Role.FACULTY)
                    .department(cse)
                    .leaveApprover(adminUser)
                    .phone("555-0101")
                    .isActive(true)
                    .firstLogin(false)
                    .build();

            userRepository.save(faculty);
            log.info("Baseline Faculty account seeded: {}", facultyEmail);
        }
    }
}
