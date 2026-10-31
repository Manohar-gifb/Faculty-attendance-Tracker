package com.college.attendance.service;

import com.college.attendance.entity.LeaveRequest;
import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.leave.approval-base-url:}")
    private String approvalBaseUrl;

    @Value("${app.mail.sender-email:${MAIL_USERNAME:admin@college.edu}}")
    private String fromEmail;

    @Value("${app.mail.sender-name:Faculty Sync Tracker}")
    private String senderName;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    /**
     * Send leave request email to HOD/Admin with portal review link (informational only)
     */
    @Async
    public void sendLeaveRequestEmail(LeaveRequest leaveRequest, User approver) {
        try {
            String portalUrl = frontendUrl + "/admin/leaves";

            Context context = new Context();
            context.setVariable("facultyName", leaveRequest.getUser().getFullName());
            context.setVariable("facultyEmail", leaveRequest.getUser().getEmail());
            context.setVariable("employeeId", leaveRequest.getUser().getEmployeeId());
            context.setVariable("departmentName",
                    leaveRequest.getUser().getDepartment() != null ? leaveRequest.getUser().getDepartment().getName() : "N/A");
            context.setVariable("leaveType", leaveRequest.getLeaveType());
            context.setVariable("startDate", leaveRequest.getStartDate());
            context.setVariable("endDate", leaveRequest.getEndDate());
            context.setVariable("durationDays", leaveRequest.getDurationDays());
            context.setVariable("reason", leaveRequest.getReason());
            context.setVariable("approverName", approver.getFullName());
            context.setVariable("websiteUrl", portalUrl);
            context.setVariable("reviewUrl", portalUrl);

            String htmlContent = templateEngine.process("leave-request-email", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(approver.getEmail());
            helper.setSubject("New Leave Request from " + leaveRequest.getUser().getFullName());
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Leave request informational email sent to {} for leave request #{}",
                    approver.getEmail(), leaveRequest.getId());

        } catch (MessagingException e) {
            log.error("Failed to send leave request email to {}: {}", approver.getEmail(), e.getMessage());
        }
    }

    /**
     * Send leave status update email to faculty
     */
    @Async
    public void sendLeaveStatusEmail(LeaveRequest leaveRequest, boolean approved) {
        try {
            User faculty = leaveRequest.getUser();

            Context context = new Context();
            context.setVariable("facultyName", faculty.getFullName());
            context.setVariable("leaveType", leaveRequest.getLeaveType());
            context.setVariable("startDate", leaveRequest.getStartDate());
            context.setVariable("endDate", leaveRequest.getEndDate());
            context.setVariable("durationDays", leaveRequest.getDurationDays());
            context.setVariable("approved", approved);
            context.setVariable("status", approved ? "APPROVED" : "REJECTED");
            context.setVariable("reviewerComments", leaveRequest.getReviewerComments());
            context.setVariable("reviewedAt", leaveRequest.getReviewedAt());
            context.setVariable("websiteUrl", frontendUrl);

            String templateName = approved ? "leave-approved-email" : "leave-rejected-email";
            String htmlContent = templateEngine.process(templateName, context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(faculty.getEmail());
            helper.setSubject("Leave Request " + (approved ? "Approved" : "Rejected") + " - " + leaveRequest.getLeaveType());
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Leave status email sent to {} for leave request #{} - Status: {}",
                    faculty.getEmail(), leaveRequest.getId(), approved ? "APPROVED" : "REJECTED");

        } catch (MessagingException e) {
            log.error("Failed to send leave status email: {}", e.getMessage());
        }
    }

    // NOTE: the old sendWelcomeEmail(...) method was removed on purpose.
    // Passwords are never emailed in this system — new users receive a
    // single-use activation link instead and choose their own password.

    /**
     * Send setup password email with a secure link for the new user to create their own password.
     * @return true only when the message was accepted by the SMTP server (no fake success)
     */
    public boolean sendSetupPasswordEmail(User user, String setupToken) {
        log.info("Attempting to send setup password email to {} ({})...", user.getFullName(), user.getEmail());

        try {
            String setupUrl = frontendUrl + "/set-password?token=" + setupToken;

            Context context = new Context();
            context.setVariable("fullName", user.getFullName());
            context.setVariable("firstName", user.getFirstName());
            context.setVariable("email", user.getEmail());
            context.setVariable("employeeId", user.getEmployeeId());
            context.setVariable("role", formatRole(user.getRole()));
            context.setVariable("departmentName",
                    user.getDepartment() != null ? user.getDepartment().getName() : "N/A");
            context.setVariable("setupUrl", setupUrl);
            context.setVariable("websiteUrl", frontendUrl);
            context.setVariable("senderName", senderName);
            context.setVariable("expiryHours", 24);

            String htmlContent = templateEngine.process("setup-password-email", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, senderName);
            helper.setTo(user.getEmail());
            helper.setSubject("Set Your Password - Faculty Sync Tracker");
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("✅ Setup password email sent successfully to {} ({})", user.getFullName(), user.getEmail());
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to send setup password email to {}: {}", user.getEmail(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Confirmation email sent after the user completed the activation (password setup).
     * Never contains passwords or tokens.
     * @return true only when the message was accepted by the SMTP server
     */
    public boolean sendActivationConfirmationEmail(User user) {
        log.info("Attempting to send activation confirmation email to {}...", user.getEmail());

        try {
            Context context = new Context();
            context.setVariable("fullName", user.getFullName());
            context.setVariable("firstName", user.getFirstName());
            context.setVariable("email", user.getEmail());
            context.setVariable("employeeId", user.getEmployeeId());
            context.setVariable("role", formatRole(user.getRole()));
            context.setVariable("departmentName",
                    user.getDepartment() != null ? user.getDepartment().getName() : "N/A");
            context.setVariable("loginUrl", frontendUrl + "/login");
            context.setVariable("websiteUrl", frontendUrl);
            context.setVariable("senderName", senderName);

            String htmlContent = templateEngine.process("activation-confirmation-email", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, senderName);
            helper.setTo(user.getEmail());
            helper.setSubject("Your Account is Activated - Faculty Sync Tracker");
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("✅ Activation confirmation email sent to {}", user.getEmail());
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to send activation confirmation email to {}: {}", user.getEmail(), e.getMessage());
            return false;
        }
    }

    /**
     * Format role enum to readable string
     */
    private String formatRole(Role role) {
        if (role == null) return "N/A";
        return switch (role) {
            case FACULTY -> "Assistant Professor";
            case HOD -> "Head of Department";
            case VC -> "Vice Chancellor";
            case PRINCIPAL -> "Principal";
            case TPO -> "Training & Placement Officer";
            case DEVELOPER -> "System Administrator";
        };
    }
}
