package com.myweb.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final ScheduledNotificationRepository scheduledRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public NotificationScheduler(ScheduledNotificationRepository scheduledRepo,
                                 KafkaTemplate<String, Object> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.scheduledRepo = scheduledRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedRate = 60000) // 每分钟检查一次
    public void processScheduledNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledNotificationEntity> dueNotifications =
                scheduledRepo.findByScheduledTimeBeforeAndProcessedFalse(now);

        if (dueNotifications.isEmpty()) {
            return;
        }

        log.info("Processing {} scheduled notifications", dueNotifications.size());

        for (ScheduledNotificationEntity entity : dueNotifications) {
            try {
                Notification notification = objectMapper.readValue(
                        entity.getNotificationJson(), Notification.class);
                kafkaTemplate.send("notification.events", notification.userId(), notification);

                entity.setProcessed(true);
                scheduledRepo.save(entity);

                log.info("Scheduled notification {} sent to queue", entity.getId());
            } catch (Exception e) {
                log.error("Failed to process scheduled notification {}: {}",
                        entity.getId(), e.getMessage());
            }
        }
    }
}