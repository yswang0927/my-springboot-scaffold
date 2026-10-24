package com.myweb.notification;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * 生产者服务 (NotificationService.java)
 * 负责保存通知意图至 MySQL 并异步写入 Kafka，按 userId 分区保证顺序。
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public NotificationService(NotificationRepository repository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(NotificationRequest request) {
        String notificationId = UUID.randomUUID().toString();
        String idempotencyKey = request.idempotencyKey() != null ? request.idempotencyKey() : UUID.randomUUID().toString();

        // 1. 持久化意图到 MySQL (PENDING)
        NotificationEntity entity = new NotificationEntity();
        entity.setId(notificationId);
        entity.setUserId(request.userId());
        entity.setChannel(request.channel());
        entity.setType(request.type());
        entity.setStatus("PENDING");
        repository.save(entity);

        // 2. 构造带重试计数的初始消息
        Notification notification = new Notification(
                notificationId,
                request.userId(),
                request.type(),
                request.channel(),
                request.templateId(),
                request.data(),
                idempotencyKey,
                0 // 初始重试次数
        );

        // 3. 异步发送至 Kafka
        kafkaTemplate.send("notification.events", notification.userId(), notification);
    }
}