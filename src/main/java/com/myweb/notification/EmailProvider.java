package com.myweb.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class EmailProvider implements NotificationProvider {
    private static final Logger log = LoggerFactory.getLogger(EmailProvider.class);

    private final JavaMailSender mailSender;

    public EmailProvider(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String getChannel() {
        return "EMAIL";
    }

    @Override
    public void send(Notification notification) throws Exception {
        String email = getUserEmail(notification.userId());
        if (email == null) {
            throw new IllegalArgumentException("User email not found for userId: " + notification.userId());
        }

        String subject = getSubject(notification.type());
        String content = renderContent(notification);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("noreply@example.com");
        message.setTo(email);
        message.setSubject(subject);
        message.setText(content);

        mailSender.send(message);
        log.info("[Email] Sent email to User: {}, Subject: {}", notification.userId(), subject);
    }

    private String getUserEmail(String userId) {
        // TODO: 从用户服务获取邮箱
        return userId + "@example.com";
    }

    private String getSubject(String type) {
        return switch (type) {
            case "OTP" -> "Your Verification Code";
            case "MARKETING" -> "Special Offer Just for You!";
            case "SECURITY" -> "Security Alert";
            default -> "Notification";
        };
    }

    private String renderContent(Notification notification) {
        // TODO: 使用模板引擎（如 Thymeleaf）渲染
        StringBuilder sb = new StringBuilder();
        sb.append("Dear User,\n\n");

        if (notification.data() != null) {
            notification.data().forEach((key, value) ->
                    sb.append(key).append(": ").append(value).append("\n"));
        }

        sb.append("\nBest regards,\nYour Team");
        return sb.toString();
    }
}