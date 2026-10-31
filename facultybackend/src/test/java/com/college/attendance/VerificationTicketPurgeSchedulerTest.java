package com.college.attendance;

import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.support.StubEmailConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3.1 Step 1 — proves the LIVE scheduled cleanup actually runs.
 *
 * {@code @Scheduled(fixedRateString = "${app.verification-ticket.purge-rate-ms:60000}")}
 * in {@link FaceVerificationService#purgeExpiredTickets()} resolves the rate from
 * configuration with a 60-second production fallback. This test context is isolated
 * (its own Spring context) and overrides the rate to 200 ms, so the automatic tick
 * must fire long before the 60 s production schedule could ever do so.
 *
 * The purge method is NEVER called manually inside this test — the only purge
 * activity observed here is the scheduler's own tick.
 */
@SpringBootTest(properties = "app.verification-ticket.purge-rate-ms=200")
@ActiveProfiles("test")
@Import(StubEmailConfiguration.class)
class VerificationTicketPurgeSchedulerTest {

    private static final long TICKET_USER_ID = 987_654_321L;
    private static final long LIVE_PURGE_WINDOW_MS = 10_000;

    @Autowired
    private FaceVerificationService faceVerificationService;

    @Test
    void scheduledCleanupEvictsExpiredTicketsAutomaticallyWithinConfiguredIntervalAndKeepsValidOnes() throws Exception {
        int baseline = faceVerificationService.getOutstandingTicketCount();

        // Issue the LONG-LIVED ticket first so no scheduler tick can evict it
        // while state is still being arranged; the cleanup only ever removes
        // tickets whose expiry instant has already lapsed.
        String validTicket = faceVerificationService.issueTicket(
                TICKET_USER_ID, 12.9716, 77.5946, 20.0, 0.0, 95.0, Duration.ofMinutes(5));
        // Negative TTL -> an already-expired ticket, exactly like one whose real
        // 2-minute TTL lapsed while its owner abandoned punch-in.
        String expiredTicket = faceVerificationService.issueTicket(
                TICKET_USER_ID, 12.9716, 77.5946, 20.0, 0.0, 95.0, Duration.ofSeconds(-1));

        assertEquals(baseline + 2, faceVerificationService.getOutstandingTicketCount(),
                "Exactly the two tickets issued above must be outstanding before the scheduler acts");

        // Wait for the SCHEDULER (no manual purge call here) to tick at the
        // short test interval and evict the expired ticket. The production
        // 60 s default could not have fired inside this 10 s window, so
        // success proves the configurable interval drives the live schedule.
        long deadline = System.currentTimeMillis() + LIVE_PURGE_WINDOW_MS;
        while (System.currentTimeMillis() < deadline
                && faceVerificationService.getOutstandingTicketCount() != baseline + 1) {
            Thread.sleep(50);
        }

        assertEquals(baseline + 1, faceVerificationService.getOutstandingTicketCount(),
                "The scheduled cleanup must automatically remove the expired ticket within "
                        + LIVE_PURGE_WINDOW_MS + " ms at the configured test rate");

        // The active ticket must survive the automatic cleanup untouched and
        // remain fully consumable by its owner; the expired one must never
        // be consumable, regardless of purge timing.
        FaceVerificationService.VerificationTicket consumed =
                faceVerificationService.consumeTicketDetails(TICKET_USER_ID, validTicket);
        assertNotNull(consumed,
                "A still-valid ticket must survive the automatic scheduled cleanup and stay usable");
        assertNull(faceVerificationService.consumeTicketDetails(TICKET_USER_ID, expiredTicket),
                "An expired ticket must never be consumable by punch-in");
    }
}
