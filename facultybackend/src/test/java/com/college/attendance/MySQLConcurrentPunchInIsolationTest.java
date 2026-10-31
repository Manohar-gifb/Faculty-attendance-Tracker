package com.college.attendance;

import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.support.StubEmailConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 3.1 Step 3 — isolated concurrent punch-in test against a REAL MySQL 8
 * instance (disposable Docker container on localhost:33071, rented only for
 * this run via container ph31-mysql-test and the database
 * college_attendance_ph31test).
 *
 * This class is gated behind the -Dph31test.enabled=true system property: it
 * is SKIPPED in every other run (in particular in the H2 regression suite), so
 * the H2 tests keep running with their own in-memory profile untouched.
 *
 * It proves, against real InnoDB behavior:
 *   1. schema: Hibernate actually creates the UNIQUE key uk_attendance_user_date;
 *   2. the enforced constraint rejects a second same-day row with a DB-layer
 *      integrity violation (never a silent success);
 *   3. concurrent HTTP punch-ins all end in documented responses — a single
 *      200 winner plus 400/409 losers — and NO request ever returns HTTP 500;
 *   4. exactly one attendance row survives the race (no duplicates).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysqltest")
@Import(StubEmailConfiguration.class)
@EnabledIfSystemProperty(named = "ph31test.enabled", matches = "true")
class MySQLConcurrentPunchInIsolationTest {

    private static final int RACERS = 6;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AttendanceLogRepository attendanceLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FaceVerificationService faceVerificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String facultyEmail;
    private final String facultyPassword = "Faculty@123";
    private Long facultyId;

    @BeforeEach
    void setUp() {
        StubEmailConfiguration.StubEmailService.reset();

        // ---------- isolation checks (fail fast, before any racing) ----------
        // The datasource must be the disposable instance, not any other DB.
        String schemaName = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals("college_attendance_ph31test", schemaName,
                "Isolation check: tests must run against college_attendance_ph31test and nothing else");
        String jdbcTemplateUrl = null;
        try {
            jdbcTemplateUrl = jdbcTemplate.getDataSource()
                    .getConnection().getMetaData().getURL();
        } catch (Exception ignored) {
            // assertion below will fail on null
        }
        assertNotNull(jdbcTemplateUrl, "Isolation check: JDBC URL must be discoverable");
        assertTrue(jdbcTemplateUrl.contains("localhost:33071"),
                "Isolation check: JDBC URL must point at the disposable container on localhost:33071");
        assertTrue(jdbcTemplateUrl.contains("college_attendance_ph31test"),
                "Isolation check: JDBC URL must point at the isolated database only");

        // ---------- race fixture ----------
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Department department = departmentRepository.findByCode("MCIT" + suffix)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("MySQL Race Dept " + suffix)
                        .code("MCIT" + suffix)
                        .isActive(true)
                        .build()));

        facultyEmail = "racecmpit." + suffix + "@test.local";
        User user = userRepository.findByEmail(facultyEmail)
                .orElseGet(() -> userRepository.save(User.builder()
                        .employeeId("MCITF" + suffix)
                        .firstName("Race")
                        .lastName("Test")
                        .email(facultyEmail)
                        .passwordHash(passwordEncoder.encode(facultyPassword))
                        .role(Role.FACULTY)
                        .department(department)
                        .isActive(true)
                        .firstLogin(false)
                        .build()));

        assertNotNull(user.getId(), "User created successfully");
        facultyId = user.getId();
    }

    private String login() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + facultyEmail + "\",\"password\":\"" + facultyPassword + "\"}"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    // =========================================================
    // §1. A UNIQUE constraint over user_id + attendance_date must
    // exist in the disposable MySQL database. (The entity's
    // @UniqueConstraint leaves naming to Hibernate, which
    // generates a name like UK44g5cgdw928ovpa29c8mkmlus, so the
    // assertion matches on COLUMN SET and UNIQUENESS, which is
    // what the punch-in duplicate guarantee actually depends on.)
    // =========================================================
    @Test
    void uniqueConstraintOverUserIdAndAttendanceDateIsCreatedAndUnique() {
        List<Map<String, Object>> ukRows = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME, COUNT(*) AS COLS, " +
                "SUM(CASE WHEN COLUMN_NAME IN ('user_id','attendance_date') THEN 1 ELSE 0 END) AS MATCH_COLS " +
                "FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance_logs' AND NON_UNIQUE = 0 " +
                "GROUP BY INDEX_NAME");

        Map<String, Object> uk = ukRows.stream()
                .filter(r -> ((Number) r.get("COLS")).intValue() == 2
                        && ((Number) r.get("MATCH_COLS")).intValue() == 2)
                .findFirst()
                .orElse(null);
        assertNotNull(uk,
                "Hibernate must create a UNIQUE index over exactly (user_id, attendance_date) on attendance_logs in MySQL");
    }

    // =========================================================
    // §2. The constraint is ENFORCED: a second same-day row for
    // the same user fails with a DB-layer integrity violation.
    // =========================================================
    @Test
    void secondSameDayRowIsRejectedByUniqueConstraint() {
        User user = userRepository.findById(facultyId).orElseThrow();
        attendanceLogRepository.save(AttendanceLog.builder()
                .user(user)
                .attendanceDate(LocalDate.now())
                .punchInTime(LocalDateTime.now())
                .status(com.college.attendance.enums.AttendanceStatus.PRESENT)
                .verificationStatus("Verified")
                .build());
        attendanceLogRepository.flush();

        assertThrows(DataIntegrityViolationException.class, () -> {
            attendanceLogRepository.save(AttendanceLog.builder()
                    .user(user)
                    .attendanceDate(LocalDate.now())
                    .punchInTime(LocalDateTime.now().plusMinutes(1))
                    .status(com.college.attendance.enums.AttendanceStatus.PRESENT)
                    .verificationStatus("Verified")
                    .build());
            attendanceLogRepository.flush();
        }, "A second same-day attendance row must violate uk_attendance_user_date");
    }

    // =========================================================
    // §3. Race: all concurrent punch-ins end in documented
    // HTTP responses (200 winner + 400/409 losers), never a 500;
    // exactly one row survives in MySQL.
    // =========================================================
    @Test
    void concurrentPunchInRaceOnRealMySqlYieldsOneWinnerAndNo500s() throws Exception {
        List<String> tickets = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            tickets.add(faceVerificationService.issueTicket(
                    facultyId, 12.9716, 77.5946, 20.0, 0.0, 95.0));
        }

        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        CountDownLatch ready = new CountDownLatch(RACERS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < RACERS; i++) {
                final String ticket = tickets.get(i);
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post("/attendance/punch-in")
                                    .header("Authorization", "Bearer " + login())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"verificationTicket\":\"" + ticket + "\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "All racing threads should become ready");
            go.countDown();

            int winners = 0;
            for (Future<Integer> outcome : outcomes) {
                int status = outcome.get(30, TimeUnit.SECONDS);
                assertTrue(status == 200 || status == 400 || status == 409,
                        "Concurrent punch-in must resolve to a documented response, never 4xx/5xx confusion. Got: " + status);
                assertTrue(status < 500,
                        "No HTTP 500 may ever be returned under concurrency (got " + status + ")");
                if (status == 200) {
                    winners++;
                }
            }
            assertEquals(1, winners,
                    "Exactly one of " + RACERS + " concurrent punch-ins may succeed against real MySQL");

            List<AttendanceLog> logsForToday = attendanceLogRepository
                    .findByUserIdAndAttendanceDateBetween(facultyId, LocalDate.now(), LocalDate.now());
            assertEquals(1, logsForToday.size(),
                    "uk_attendance_user_date must guarantee exactly one attendance row for the day");
            assertNotNull(logsForToday.get(0).getPunchInTime(),
                    "The surviving row must hold the recorded punch-in");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "Race threads should terminate promptly");
        }
    }
}
