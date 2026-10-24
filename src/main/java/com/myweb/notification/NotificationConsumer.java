package com.myweb.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

/**
 * 消费者与幂等处理 (NotificationConsumer.java)
 * 利用 Redis 的 SETNX（setIfAbsent）实现 effectively-once 幂等消费。
 */
@Component
public class NotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationRepository repository;
    private final NotificationDispatcher dispatcher;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final int MAX_RETRIES = 3;

    public NotificationConsumer(StringRedisTemplate redisTemplate,
                                NotificationRepository repository,
                                NotificationDispatcher dispatcher,
                                KafkaTemplate<String, Object> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "notification.events", groupId = "push-worker-group")
    public void consume(Notification notification) {
        // 1. Redis 幂等校验 (SETNX)
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(notification.idempotencyKey(), "PROCESSING", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isNew)) {
            log.warn("Duplicate notification event detected, skipped: {}", notification.idempotencyKey());
            return;
        }

        // 2. 简易限流保护（例如：控制单用户每分钟最多接收 5 条营销通知，OTP 不受限）
        if ("MARKETING".equals(notification.type())) {
            String rateLimitKey = "rate_limit:" + notification.userId();
            Long count = redisTemplate.opsForValue().increment(rateLimitKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateLimitKey, Duration.ofMinutes(1));
            }
            if (count != null && count > 5) {
                log.warn("Rate limit exceeded for user: {}, dropping/delaying marketing notification", notification.userId());
                // 可选择重新投递回延迟队列或直接标记拒绝
                return;
            }
        }

        try {
            // 3. 通过 Provider 分发发送
            dispatcher.dispatch(notification);

            // 4. 更新数据库状态为 SENT
            updateDbStatus(notification.notificationId(), "SENT");
            redisTemplate.opsForValue().set(notification.idempotencyKey(), "SUCCESS", Duration.ofHours(24));

        } catch (Exception e) {
            log.error("Failed to send notification (Attempt {}/{}): {}", notification.retryCount() + 1, MAX_RETRIES, notification.notificationId(), e);
            handleRetryOrDlq(notification);
        }
    }

    private void handleRetryOrDlq(Notification notification) {
        if (notification.retryCount() < MAX_RETRIES) {
            // 构造重试消息， retryCount + 1
            Notification retryNotification = new Notification(
                    notification.notificationId(),
                    notification.userId(),
                    notification.type(),
                    notification.channel(),
                    notification.templateId(),
                    notification.data(),
                    notification.idempotencyKey(),
                    notification.retryCount() + 1
            );
            // 投递至重试主题或延迟队列（此处简化直接投回原队列或重试Topic）
            kafkaTemplate.send("notification.retry", retryNotification.userId(), retryNotification);
        } else {
            // 超过最大重试次数，进入死信队列 (DLQ) 并更新数据库状态为 FAILED
            log.error("Notification reached max retries, moving to DLQ: {}", notification.notificationId());
            updateDbStatus(notification.notificationId(), "FAILED");
            kafkaTemplate.send("notification.dlq", notification.userId(), notification);
            redisTemplate.opsForValue().set(notification.idempotencyKey(), "FAILED", Duration.ofHours(24));
        }
    }

    private void updateDbStatus(String id, String status) {
        repository.findById(id).ifPresent(entity -> {
            entity.setStatus(status);
            repository.save(entity);
        });
    }
}
