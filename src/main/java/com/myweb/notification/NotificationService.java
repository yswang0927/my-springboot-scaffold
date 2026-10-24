package com.myweb.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final ScheduledNotificationRepository scheduledRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repository,
                               ScheduledNotificationRepository scheduledRepository,
                               KafkaTemplate<String, Object> kafkaTemplate,
                               MeterRegistry meterRegistry,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.scheduledRepository = scheduledRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
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
        entity.setTemplateId(request.templateId());
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRetryCount(0);
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
                0
        );

        // 3. 异步发送至 Kafka
        kafkaTemplate.send("notification.events", notification.userId(), notification);

        // 4. 记录接收指标
        Counter.builder("notification.received")
                .tag("channel", request.channel())
                .tag("type", request.type())
                .register(meterRegistry)
                .increment();
    }

    public void schedule(NotificationRequest request, LocalDateTime scheduledTime) {
        String notificationId = UUID.randomUUID().toString();
        String idempotencyKey = request.idempotencyKey() != null ? request.idempotencyKey() : UUID.randomUUID().toString();

        Notification notification = new Notification(
                notificationId,
                request.userId(),
                request.type(),
                request.channel(),
                request.templateId(),
                request.data(),
                idempotencyKey,
                0
        );

        try {
            ScheduledNotificationEntity entity = new ScheduledNotificationEntity();
            entity.setId(notificationId);
            entity.setScheduledTime(scheduledTime);
            entity.setNotificationJson(objectMapper.writeValueAsString(notification));
            entity.setProcessed(false);
            scheduledRepository.save(entity);

            // 同时保存到主表用于跟踪
            NotificationEntity mainEntity = new NotificationEntity();
            mainEntity.setId(notificationId);
            mainEntity.setUserId(request.userId());
            mainEntity.setChannel(request.channel());
            mainEntity.setType(request.type());
            mainEntity.setTemplateId(request.templateId());
            mainEntity.setIdempotencyKey(idempotencyKey);
            mainEntity.setRetryCount(0);
            mainEntity.setStatus("SCHEDULED");
            repository.save(mainEntity);

            Counter.builder("notification.scheduled")
                    .tag("channel", request.channel())
                    .tag("type", request.type())
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            throw new RuntimeException("Failed to schedule notification", e);
        }
    }
}