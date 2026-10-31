package com.college.attendance.support;

import com.college.attendance.entity.User;
import com.college.attendance.service.EmailService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only replacement for {@link EmailService}.
 *
 * Mockito cannot instrument classes on newer JDKs in this project's test setup,
 * so a hand-written stub is registered as the @Primary bean instead. It never
 * touches the network and records what would have been sent.
 *
 * State is STATIC on purpose: Spring wraps the bean in a CGLIB proxy, so the
 * test and the application must share state through the class, not through
 * instance fields of the proxy.
 */
@Configuration(proxyBeanMethods = false)
public class StubEmailConfiguration {

    @Bean
    @Primary
    public StubEmailService stubEmailService() {
        return new StubEmailService();
    }

    public static class StubEmailService extends EmailService {

        /** Controls the delivery outcome returned by sendSetupPasswordEmail. */
        public static volatile boolean setupEmailResult = true;

        public static volatile int setupEmailCount = 0;
        public static volatile int confirmationEmailCount = 0;
        public static volatile String lastSetupRecipient = null;
        /** Raw one-time token of the most recent setup email (test-only capture). */
        public static volatile String lastSetupToken = null;

        public StubEmailService() {
            super(null, null);
        }

        @Override
        public boolean sendSetupPasswordEmail(User user, String setupToken) {
            setupEmailCount++;
            lastSetupRecipient = user != null ? user.getEmail() : null;
            lastSetupToken = setupToken;
            return setupEmailResult;
        }

        @Override
        public boolean sendActivationConfirmationEmail(User user) {
            confirmationEmailCount++;
            return true;
        }

        @Override
        public void sendLeaveRequestEmail(com.college.attendance.entity.LeaveRequest leaveRequest, User approver) {
            // not used in these tests
        }

        @Override
        public void sendLeaveStatusEmail(com.college.attendance.entity.LeaveRequest leaveRequest, boolean approved) {
            // not used in these tests
        }

        public static void reset() {
            setupEmailResult = true;
            setupEmailCount = 0;
            confirmationEmailCount = 0;
            lastSetupRecipient = null;
            lastSetupToken = null;
        }
    }
}
