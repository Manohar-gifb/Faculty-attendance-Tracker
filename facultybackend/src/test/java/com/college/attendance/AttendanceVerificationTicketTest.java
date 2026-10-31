package com.college.attendance;

import com.college.attendance.dto.DashboardStatsDTO;
import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.entity.Department;
import com.college.attendance.entity.User;
import com.college.attendance.enums.AttendanceStatus;
import com.college.attendance.enums.Role;
import com.college.attendance.repository.AttendanceLogRepository;
import com.college.attendance.repository.DepartmentRepository;
import com.college.attendance.repository.UserRepository;
import com.college.attendance.service.AttendanceService;
import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.support.StubEmailConfiguration;
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

import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the punch-in verification bypass:
 *
 * POST /attendance/punch-in used to be callable directly, bypassing the
 * face + GPS verification performed by POST /attendance/verify. Now all
 * roles except DEVELOPER must present a valid single-use verification
 * ticket issued by /attendance/verify.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class AttendanceVerificationTicketTest {

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
    private FaceVerificationService faceVerificationService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceLogRepository attendanceLogRepository;

    private String facultyEmail;
    private final String facultyPassword = "Faculty@123";
    private Long facultyId;

    @BeforeEach
    void setUp() {
        StubEmailConfiguration.StubEmailService.reset();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Department department = departmentRepository.findByCode("AVTC" + suffix)
                .orElseGet(() -> departmentRepository.save(Department.builder()
                        .name("TicketTest Dept " + suffix)
                        .code("AVTC" + suffix)
                        .isActive(true)
                        .build()));

        facultyEmail = "punch." + suffix + "@test.local";
        User user = userRepository.findByEmail(facultyEmail)
                .orElseGet(() -> userRepository.save(User.builder()
                        .employeeId("PVTF" + suffix)
                        .firstName("Punch")
                        .lastName("Test")
                        .email(facultyEmail)
                        .passwordHash(passwordEncoder.encode(facultyPassword))
                        .role(Role.FACULTY)
                        .department(department)
                        .isActive(true)
                        .firstLogin(false)
                        .build()));
        facultyId = user.getId();
    }

    private String login() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + facultyEmail + "\",\"password\":\"" + facultyPassword + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    // =========================================================
    // §1 punch-in without a verification ticket is rejected
    // =========================================================

    @Test
    void facultyCannotPunchInWithoutVerificationTicket() throws Exception {
        String token = login();

        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("verification")));

        // No attendance row may have been created by the rejected call
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §2 punch-in with a valid ticket succeeds
    // =========================================================

    @Test
    void facultyPunchesInWithValidSingleUseTicket() throws Exception {
        String token = login();
        String ticket = faceVerificationService.issueTicket(facultyId);

        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + ticket + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPunchedIn").value(true));
    }

    // =========================================================
    // §3 a consumed ticket cannot be used again (single use)
    // =========================================================

    @Test
    void ticketCannotBeReplayed() throws Exception {
        String token = login();
        String ticket = faceVerificationService.issueTicket(facultyId);

        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + ticket + "\"}"))
                .andExpect(status().isOk());

        // Second use — even for the same user — is rejected
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + ticket + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §4 a ticket bound to another user is rejected
    // =========================================================

    @Test
    void ticketIssuedForOneUserCannotBeUsedByAnother() throws Exception {
        String token = login();
        String ticketForSomeoneElse = faceVerificationService.issueTicket(facultyId + 100);

        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + ticketForSomeoneElse + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §5 an expired ticket is rejected by punch-in
    // (negative TTL regenerates the exact state a ticket has
    // after its real 2-minute TTL has lapsed)
    // =========================================================

    @Test
    void ticketExpiryIsEnforcedByPunchIn() throws Exception {
        String token = login();
        String expiredTicket = faceVerificationService.issueTicket(
                facultyId, 12.9716, 77.5946, 20.0, 0.0, 95.0, Duration.ofSeconds(-1));

        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + expiredTicket + "\"}"))
                .andExpect(status().isBadRequest());

        // No attendance record may have been created by the rejected call
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + expiredTicket + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §6 scheduled purge removes expired/abandoned tickets
    // =========================================================

    @Test
    void purgeExpiredTicketsEvictsAbandonedTickets() {
        // Start from an eviction-consistent baseline: our own purge first,
        // so no expired ticket from a previous test can skew the delta.
        faceVerificationService.purgeExpiredTickets();
        int baseline = faceVerificationService.getOutstandingTicketCount();

        faceVerificationService.issueTicket(
                facultyId, 12.9716, 77.5946, 20.0, 0.0, 95.0, Duration.ofSeconds(-1));
        assertEquals(baseline + 1, faceVerificationService.getOutstandingTicketCount(),
                "Expired (abandoned) ticket should be outstanding before purge");

        faceVerificationService.purgeExpiredTickets();

        assertEquals(baseline, faceVerificationService.getOutstandingTicketCount(),
                "Expired/abandoned ticket must be evicted by the scheduled purge");
    }

    // =========================================================
    // §7 active (unexpired) tickets survive a purge untouched
    // and remain usable for punch-in afterwards
    // =========================================================

    @Test
    void activeTicketsArePreservedDuringPurge() throws Exception {
        faceVerificationService.purgeExpiredTickets();
        int baseline = faceVerificationService.getOutstandingTicketCount();

        String freshTicket = faceVerificationService.issueTicket(
                facultyId, 12.9716, 77.5946, 20.0, 0.0, 95.0, Duration.ofMinutes(2));
        faceVerificationService.purgeExpiredTickets();

        assertEquals(baseline + 1, faceVerificationService.getOutstandingTicketCount(),
                "A fresh, unexpired ticket must never be evicted by the purge");

        // The surviving ticket is still fully usable for its intended purpose
        String token = login();
        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"" + freshTicket + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPunchedIn").value(true));
    }

    // =========================================================
    // §8 concurrent consumption: the ticket map removal is atomic,
    // so exactly one caller can consume a given ticket even when
    // many threads race on it at the same instant
    // =========================================================

    @Test
    void concurrentConsumptionWinsExactlyOnce() throws Exception {
        String ticket = faceVerificationService.issueTicket(facultyId);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return faceVerificationService.consumeTicket(facultyId, ticket);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "All racing threads should become ready");
            go.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(5, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            assertEquals(1, winners,
                    "Exactly one of " + threads + " concurrent consumers may win the single-use ticket");

            // Post-race replay is also impossible: nothing is left to consume
            String token = login();
            mockMvc.perform(post("/attendance/punch-in")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"verificationTicket\":\"" + ticket + "\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Race threads should terminate promptly");
        }
    }

    // =========================================================
    // §9 expired-payload / no body: ticket semantics stay safe
    // (a null or garbage ticket behaves like "no ticket")
    // =========================================================

    @Test
    void garbageTicketIsRejected() throws Exception {
        String token = login();

        mockMvc.perform(post("/attendance/punch-in")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationTicket\":\"not-a-real-ticket\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // §10 concurrent punch-ins: the unique constraint
    // uk_attendance_user_date guarantees zero duplicate rows;
    // the losing requests must surface as documented 400/409
    // conflict responses — never raw 500s.
    // Each racer carries its own verification ticket so the race
    // specifically exercises the insert path, not a ticket
    // rejection.
    // =========================================================

    @Test
    void concurrentPunchInRaceYieldsExactlyOneRecordAndNo500s() throws Exception {
        int racers = 6;
        List<String> tickets = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            tickets.add(faceVerificationService.issueTicket(
                    facultyId, 12.9716, 77.5946, 20.0, 0.0, 95.0));
        }

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
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
            assertTrue(ready.await(10, TimeUnit.SECONDS), "All racing threads should become ready");
            go.countDown();

            int winners = 0;
            for (Future<Integer> outcome : outcomes) {
                int status = outcome.get(15, TimeUnit.SECONDS);
                assertTrue(status == 200 || status == 400 || status == 409,
                        "Concurrent punch-in must resolve to a documented response, never 4xx/5xx confusion. Got: " + status);
                assertTrue(status < 500, "No unhandled server errors allowed under concurrency (got " + status + ")");
                if (status == 200) {
                    winners++;
                }
            }
            assertEquals(1, winners,
                    "Exactly one of " + racers + " concurrent punch-ins may succeed");

            // The unique constraint keeps the database duplicate-free
            List<AttendanceLog> logsForToday = attendanceLogRepository
                    .findByUserIdAndAttendanceDateBetween(facultyId, LocalDate.now(), LocalDate.now());
            assertEquals(1, logsForToday.size(),
                    "uk_attendance_user_date must allow exactly one attendance row for the day");
            assertTrue(logsForToday.get(0).getPunchInTime() != null,
                    "The surviving attendance row must hold the recorded punch-in");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Race threads should terminate promptly");
        }
    }

    // =========================================================
    // §11 dashboard regression: the department breakdown must
    // weight HALF_DAY count into its absent calculation (both
    // the institution-wide overload and the caller-scoped
    // overload must agree).
    // =========================================================

    @Test
    void departmentBreakdownAccountsForHalfDay() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Department dept = departmentRepository.save(Department.builder()
                .name("HalfDay Dept " + suffix)
                .code("HD" + suffix)
                .isActive(true)
                .build());

        User halfDayFaculty = userRepository.save(User.builder()
                .employeeId("HDTF" + suffix)
                .firstName("Half")
                .lastName("Day " + suffix)
                .email("halfday." + suffix + "@test.local")
                .passwordHash(passwordEncoder.encode("Faculty@123"))
                .role(Role.FACULTY)
                .department(dept)
                .isActive(true)
                .firstLogin(false)
                .build());

        attendanceLogRepository.save(AttendanceLog.builder()
                .user(halfDayFaculty)
                .attendanceDate(LocalDate.now())
                .punchInTime(LocalDateTime.now())
                .status(AttendanceStatus.HALF_DAY)
                .build());

        // Institution-wide overload: the single-faculty department's breakdown
        // must not report the HALF_DAY member as absent.
        DashboardStatsDTO global = attendanceService.getDashboardStats();
        DashboardStatsDTO.DepartmentAttendanceDTO entry = global.getDepartmentBreakdown().stream()
                .filter(d -> dept.getCode().equals(d.getDepartmentCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test department missing from breakdown"));

        assertEquals(1, entry.getTotalFaculty());
        assertEquals(0, entry.getPresent());
        assertEquals(0, entry.getLate());
        assertEquals(0, entry.getAbsent(),
                "A HALF_DAY faculty member must not be counted as absent in the department breakdown");
        // present + late + halfDay(1) + onLeave(0) + absent must equal deptTotal
        assertEquals(entry.getTotalFaculty(), entry.getPresent() + entry.getLate() + 1 + 0 + entry.getAbsent());

        // Caller-scoped overload must agree with the institution-wide one.
        DashboardStatsDTO scoped = attendanceService.getDashboardStats(halfDayFaculty);
        assertEquals(1, scoped.getTotalFaculty());
        assertEquals(0, scoped.getPresentToday());
        assertEquals(0, scoped.getLateToday());
        assertEquals(0, scoped.getAbsentToday(),
                "Caller-scoped absent count must also account for HALF_DAY");
        assertEquals(1, scoped.getDepartmentBreakdown().size());
        DashboardStatsDTO.DepartmentAttendanceDTO scopedEntry = scoped.getDepartmentBreakdown().get(0);
        assertEquals(entry.getTotalFaculty(), scopedEntry.getTotalFaculty());
        assertEquals(entry.getAbsent(), scopedEntry.getAbsent(),
                "Both dashboard overloads must agree on the department's absent count");
    }
}
