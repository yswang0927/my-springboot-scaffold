package com.myweb.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class InAppProvider implements NotificationProvider {
    private static final Logger log = LoggerFactory.getLogger(InAppProvider.class);

    private final SimpMessagingTemplate messagingTemplate;

    public InAppProvider(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public String getChannel() {
        return "IN_APP";
    }

    @Override
    public void send(Notification notification) throws Exception {
        // 通过 WebSocket 发送给在线用户
        String destination = "/user/" + notification.userId() + "/queue/notifications";
        messagingTemplate.convertAndSend(destination, notification);
        log.info("[In-App] Sent in-app notification to User: {}", notification.userId());
    }
}
