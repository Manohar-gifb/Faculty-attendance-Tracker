package com.college.attendance;

import com.college.attendance.dto.DashboardStatsDTO;
import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.LeaveRequest;
import com.college.attendance.entity.User;
import com.college.attendance.enums.AttendanceStatus;
import com.college.attendance.enums.LeaveStatus;
import com.college.attendance.enums.LeaveType;
import com.college.attendance.enums.Role;
import com.college.attendance.exception.DuplicateAttendanceException;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.LeaveRequestRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.AttendanceService;
import com.college.attendance.support.StubEmailConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3.1 Step 2 — the three remaining test-only regression gaps.
 *
 * 1. The duplicate-attendance HTTP 409 response body keeps its documented
 *    {@code {message, status, timestamp}} structure.
 * 2. A HALF_DAY attendance record overlapping approved leave keeps every
 *    absent count non-negative and both dashboard-statistics overloads agree.
 * 3. Concurrent punch-out is documented last-writer-wins: no 500s, exactly
 *    one attendance row, and internally consistent working hours. Per the
 *    Phase 3.1 constraints, NO locking or schema change is introduced here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class AttendanceRegressionGapsTest {

    /**
     * Test-only probe endpoint. It exists solely so the REAL
     * {@code GlobalExceptionHandler @RestControllerAdvice} (not a mock) is exercised
     * through the full HTTP pipeline for {@link DuplicateAttendanceException},
     * deterministically and without requiring the timing-dependent punch-in race
     * to land on the 409 branch (the race itself is covered separately and accepts
     * both the documented 400 and 409 outcomes).
     */
    @TestConfiguration
    static class DuplicateProbeConfiguration {

        @RestController
        static class DuplicateAttendanceProbeController {

            @GetMapping("/test-only/duplicate-attendance")
            public String probe() {
                throw new DuplicateAttendanceException("Attendance record already exists for today");
            }
        }
    }

    private static final String FACULTY_PASSWORD = "Faculty@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AttendanceLogRepository attendanceLogRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AttendanceService attendanceService;

    /** Isolated per-test user in a unique department, mirroring the existing test conventions. */
    private record Fixture(Department department, User faculty) {}

    private Fixture createFacultyFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Department department = departmentRepository.findByCode("RGT" + suffix)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("Phase31 Regression Dept " + suffix)
                        .code("RGT" + suffix)
                        .isActive(true)
                        .build()));
        User faculty = userRepository.save(User.builder()
                .employeeId("P31T" + suffix)
                .firstName("Phase")
                .lastName("31 Regression " + suffix)
                .email("phase31." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode(FACULTY_PASSWORD))
                .role(Role.FACULTY)
                .department(department)
                .isActive(true)
                .firstLogin(false)
                .build());
        return new Fixture(department, faculty);
    }

    private String login(User faculty) throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + faculty.getEmail() + "\",\"password\":\"" + FACULTY_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    // =========================================================
    // §1 Duplicate-attendance HTTP 409 response body contract:
    // {message, status, timestamp} — exactly the structure the
    // frontend reads. Verified through the real exception-handler
    // wiring, attributed to the real DuplicateAttendanceException.
    // =========================================================

    @Test
    void duplicateAttendanceMapsTo409WithMessageStatusTimestampBody() throws Exception {
        Fixture fixture = createFacultyFixture();
        String token = login(fixture.faculty());

        var response = mockMvc.perform(get("/test-only/duplicate-attendance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andReturn();

        var body = objectMapper.readTree(response.getResponse().getContentAsString());
        assertEquals("Attendance record already exists for today", body.get("message").asText(),
                "The 409 body must carry the documented duplicate-attendance message");
        assertEquals(409, body.get("status").asInt(),
                "The 409 body must expose the integer status the frontend reads");
        assertTrue(body.has("timestamp") && !body.get("timestamp").asText().isBlank(),
                "The 409 body must include the timestamp field");
        assertDoesNotThrow(() -> Instant.parse(body.get("timestamp").asText()),
                "timestamp must remain a parseable ISO-8601 instant");
    }

    // =========================================================
    // §2 HALF_DAY overlapping approved leave: absent counts stay
    // non-negative, the faculty member is not double-counted as
    // absent, and BOTH dashboard overloads agree.
    // =========================================================

    @Test
    void halfDayAttendanceOverlappingApprovedLeaveKeepsAbsentCountsNonNegativeInBothDashboardOverloads() {
        Fixture fixture = createFacultyFixture();
        User faculty = fixture.faculty();
        Department department = fixture.department();
        LocalDate today = LocalDate.now();

        attendanceLogRepository.save(AttendanceLog.builder()
                .user(faculty)
                .attendanceDate(today)
                .punchInTime(LocalDateTime.now())
                .status(AttendanceStatus.HALF_DAY)
                .build());

        leaveRequestRepository.save(LeaveRequest.builder()
                .user(faculty)
                .leaveType(LeaveType.CASUAL)
                .reason("Phase 3.1 dashboard-overlap regression test")
                .startDate(today.minusDays(1))
                .endDate(today.plusDays(2))
                .status(LeaveStatus.APPROVED)
                .build());

        // Institution-wide overload: the faculty member is BOTH logged HALF_DAY
        // and covered by an approved leave today. The overlap must never drive
        // the absent count negative and must not report them absent.
        DashboardStatsDTO global = attendanceService.getDashboardStats();
        assertTrue(global.getAbsentToday() >= 0,
                "Institution-wide absentToday must never go negative under HALF_DAY + approved-leave overlap");
        assertTrue(global.getOnLeaveToday() >= 1,
                "The faculty member on approved leave must be counted in onLeaveToday");
        DashboardStatsDTO.DepartmentAttendanceDTO globalDept = global.getDepartmentBreakdown().stream()
                .filter(d -> department.getCode().equals(d.getDepartmentCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test department missing from institution-wide breakdown"));
        assertTrue(globalDept.getAbsent() >= 0,
                "Department breakdown absent must never go negative under HALF_DAY + approved-leave overlap");
        assertEquals(1, globalDept.getTotalFaculty());
        assertEquals(0, globalDept.getAbsent(),
                "A HALF_DAY faculty member overlapping approved leave must NOT be reported absent");

        // Caller-scoped overload: this is a single-faculty department, so the
        // scoped totals must mirror the institution-wide breakdown entry.
        DashboardStatsDTO scoped = attendanceService.getDashboardStats(faculty);
        assertEquals(1, scoped.getTotalFaculty());
        assertEquals(0, scoped.getPresentToday());
        assertEquals(0, scoped.getLateToday());
        assertEquals(1, scoped.getOnLeaveToday(),
                "Caller-scoped onLeaveToday must count the overlapping approved leave");
        assertTrue(scoped.getAbsentToday() >= 0,
                "Caller-scoped absentToday must never go negative under HALF_DAY + approved-leave overlap");
        assertEquals(0, scoped.getAbsentToday(),
                "Caller-scoped absentToday must not double-subtract the HALF_DAY + on-leave faculty member");

        // Both overloads must agree on the department they both describe.
        assertEquals(1, scoped.getDepartmentBreakdown().size());
        DashboardStatsDTO.DepartmentAttendanceDTO scopedDept = scoped.getDepartmentBreakdown().get(0);
        assertEquals(globalDept.getTotalFaculty(), scopedDept.getTotalFaculty(),
                "Both dashboard overloads must agree on department total faculty");
        assertEquals(globalDept.getPresent(), scopedDept.getPresent(),
                "Both dashboard overloads must agree on department present count");
        assertEquals(globalDept.getAbsent(), scopedDept.getAbsent(),
                "Both dashboard overloads must agree on department absent count");
        assertEquals(globalDept.getPresent(), scoped.getPresentToday(),
                "Scoped totals and scoped breakdown must be internally consistent");
    }

    // =========================================================
    // §3 Concurrent punch-out: DOCUMENTED LAST-WRITER-WINS.
    // The check-then-act structure means two simultaneous punch-outs
    // can both succeed and the second write simply overwrites
    // punchOutTime / workingHours. This test locks in the safety
    // properties that DO hold: no 500s, exactly one attendance row,
    // and a row whose working hours match its own punch-in/out pair.
    // No locking and no schema change are added here per plan.
    // =========================================================

    @Test
    void concurrentPunchOutKeepsExactlyOneRecordWithConsistentHoursAndNoServerErrors() throws Exception {
        Fixture fixture = createFacultyFixture();
        User faculty = fixture.faculty();
        attendanceLogRepository.save(AttendanceLog.builder()
                .user(faculty)
                .attendanceDate(LocalDate.now())
                .punchInTime(LocalDateTime.now())
                .status(AttendanceStatus.PRESENT)
                .build());

        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post("/attendance/punch-out")
                                    .header("Authorization", "Bearer " + login(faculty))
                                    .contentType(MediaType.APPLICATION_JSON))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "All racing threads should become ready");
            go.countDown();

            int successes = 0;
            for (Future<Integer> outcome : outcomes) {
                int httpStatus = outcome.get(15, TimeUnit.SECONDS);
                assertTrue(httpStatus == 200 || httpStatus == 400,
                        "Concurrent punch-out must resolve to a documented response (200 or the sequential "
                                + "duplicate guard 400), never a 500. Got: " + httpStatus
                                + " — last-writer-wins means both racers MAY succeed.");
                assertTrue(httpStatus < 500,
                        "No unhandled server errors allowed for concurrent punch-out (got " + httpStatus + ")");
                if (httpStatus == 200) {
                    successes++;
                }
            }
            assertTrue(successes >= 1,
                    "At least one concurrent punch-out must record the punch-out (first writer)");
            // successes == 2 is the documented last-writer-wins outcome: both
            // racers passed the open punch-out pre-check before either
            // committed, and the second write overwrote the first. Accepted
            // behavior per the Phase 3.1 plan — not hardened here.
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Race threads should terminate promptly");
        }

        // Safety invariants: exactly one row for the day, with punch-out and
        // internally consistent working hours no matter which write won.
        List<AttendanceLog> logsForToday = attendanceLogRepository
                .findByUserIdAndAttendanceDateBetween(faculty.getId(), LocalDate.now(), LocalDate.now());
        assertEquals(1, logsForToday.size(),
                "Concurrent punch-out must never create duplicate attendance rows");
        AttendanceLog record = logsForToday.get(0);
        assertNotNull(record.getPunchOutTime(),
                "The single surviving record must carry the recorded punch-out");
        assertNotNull(record.getPunchInTime(), "The record must retain its punch-in");
        long minutes = Duration.between(record.getPunchInTime(), record.getPunchOutTime()).toMinutes();
        assertEquals(0, BigDecimal.valueOf(minutes / 60.0).setScale(2, RoundingMode.HALF_UP)
                        .compareTo(record.getWorkingHours()),
                "Persisted working hours must match the persisted punch-in/punch-out pair of the winning write");

        // The sequential duplicate guard behaves as documented after the race:
        // any later punch-out is rejected 400, never 500.
        mockMvc.perform(post("/attendance/punch-out")
                        .header("Authorization", "Bearer " + login(faculty))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Already punched out for today"));
    }
}
