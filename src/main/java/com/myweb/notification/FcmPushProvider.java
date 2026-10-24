package com.myweb.notification;

import org.springframework.stereotype.Component;


/**
 * FCM 推送提供商
 */
@Component
public class FcmPushProvider implements NotificationProvider {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FcmPushProvider.class);

    @Override
    public String getChannel() {
        return "PUSH";
    }

    @Override
    public void send(Notification notification) throws Exception {
        log.info("[FCM] Sending Push Notification to User: {}, Data: {}", notification.userId(), notification.data());
        // 模拟偶尔发生第三方限流或网络抖动，触发重试
        if (Math.random() < 0.2) {
            throw new RuntimeException("FCM Gateway Timeout / Rate Limited");
        }
    }
}