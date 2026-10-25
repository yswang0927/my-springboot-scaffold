package com.myweb.notification;

import org.springframework.stereotype.Component;

@Component
public class SmsProvider implements NotificationProvider {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SmsProvider.class);

    @Override
    public String getChannel() {
        return "SMS";
    }

    @Override
    public void send(Notification notification) throws Exception {
        log.info("[Twilio] Sending SMS to User: {}", notification.userId());
    }
}
