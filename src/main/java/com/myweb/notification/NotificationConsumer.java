package com.myweb.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class NotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationRepository repository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final NotificationDispatcher dispatcher;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private static final int MAX_RETRIES = 3;

    public NotificationConsumer(StringRedisTemplate redisTemplate,
                                NotificationRepository repository,
                                UserPreferenceRepository userPreferenceRepository,
                                NotificationDispatcher dispatcher,
                                KafkaTemplate<String, Object> kafkaTemplate,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.dispatcher = dispatcher;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "notification.events", groupId = "notification-worker-group")
    public void consume(Notification notification) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 1. Redis 幂等校验 (SETNX)
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(notification.idempotencyKey(), "PROCESSING", Duration.ofHours(24));

            if (Boolean.FALSE.equals(isNew)) {
                log.warn("Duplicate notification event detected, skipped: {}", notification.idempotencyKey());
                return;
            }

            // 2. 检查用户偏好
            if (!checkUserPreference(notification)) {
                updateDbStatus(notification.notificationId(), "BLOCKED", null);
                redisTemplate.opsForValue().set(notification.idempotencyKey(), "BLOCKED", Duration.ofHours(24));
                return;
            }

            // 3. 限流保护
            if (!checkRateLimit(notification)) {
                log.warn("Rate limit exceeded for user: {}, dropping notification", notification.userId());
                updateDbStatus(notification.notificationId(), "RATE_LIMITED", null);
                redisTemplate.opsForValue().set(notification.idempotencyKey(), "RATE_LIMITED", Duration.ofHours(24));
                return;
            }

            // 4. 通过 Provider 分发发送
            dispatcher.dispatch(notification);

            // 5. 更新数据库状态为 SENT
            updateDbStatus(notification.notificationId(), "SENT", null);
            redisTemplate.opsForValue().set(notification.idempotencyKey(), "SUCCESS", Duration.ofHours(24));

            // 6. 记录成功指标
            Counter.builder("notification.sent.success")
                    .tag("channel", notification.channel())
                    .tag("type", notification.type())
                    .register(meterRegistry)
                    .increment();

        } catch (Exception e) {
            log.error("Failed to send notification (Attempt {}/{}): {}",
                    notification.retryCount() + 1, MAX_RETRIES, notification.notificationId(), e);

            // 记录失败指标
            Counter.builder("notification.sent.failure")
                    .tag("channel", notification.channel())
                    .tag("error", e.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();

            handleRetryOrDlq(notification, e.getMessage());
        } finally {
            sample.stop(Timer.builder("notification.send.duration")
                    .tag("channel", notification.channel())
                    .register(meterRegistry));
        }
    }

    private boolean checkUserPreference(Notification notification) {
        UserPreferenceEntity prefs = userPreferenceRepository.findById(notification.userId())
                .orElseGet(() -> {
                    UserPreferenceEntity defaultPrefs = new UserPreferenceEntity();
                    defaultPrefs.setUserId(notification.userId());
                    return defaultPrefs;
                });

        // 检查渠道是否启用
        return switch (notification.channel()) {
            case "EMAIL" -> prefs.isEmailEnabled();
            case "SMS" -> prefs.isSmsEnabled();
            case "PUSH" -> prefs.isPushEnabled();
            case "IN_APP" -> prefs.isInAppEnabled();
            default -> true;
        };
    }

    private boolean checkRateLimit(Notification notification) {
        // OTP 等关键通知不受限
        if ("OTP".equals(notification.type()) || "SECURITY".equals(notification.type())) {
            return true;
        }

        // 营销通知限制
        if ("MARKETING".equals(notification.type())) {
            UserPreferenceEntity prefs = userPreferenceRepository.findById(notification.userId())
                    .orElse(null);
            int dailyLimit = prefs != null ? prefs.getDailyMarketingLimit() : 5;

            String dailyLimitKey = "daily_limit:" + notification.userId() + ":" + LocalDateTime.now().toLocalDate();
            Long count = redisTemplate.opsForValue().increment(dailyLimitKey);
            if (count != null && count == 1) {
                redisTemplate.expire(dailyLimitKey, Duration.ofDays(1));
            }
            if (count != null && count > dailyLimit) {
                log.warn("Daily marketing limit exceeded for user: {}", notification.userId());
                return false;
            }
        }

        // 通用频率限制：每分钟最多 N 条
        UserPreferenceEntity prefs = userPreferenceRepository.findById(notification.userId())
                .orElse(null);
        int minuteLimit = prefs != null ? prefs.getMinuteRateLimit() : 10;

        String rateLimitKey = "rate_limit:" + notification.userId();
        Long count = redisTemplate.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redisTemplate.expire(rateLimitKey, Duration.ofMinutes(1));
        }
        return count == null || count <= minuteLimit;
    }

    private void handleRetryOrDlq(Notification notification, String errorMessage) {
        if (notification.retryCount() < MAX_RETRIES) {
            // 指数退避：2^retryCount 秒（2秒、4秒、8秒）
            long delaySeconds = (long) Math.pow(2, notification.retryCount());

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

            // 发送到延迟重试主题
            String retryTopic = "notification.retry." + delaySeconds + "s";
            log.info("Scheduling retry for notification {} to topic {} after {} seconds",
                    notification.notificationId(), retryTopic, delaySeconds);
            kafkaTemplate.send(retryTopic, retryNotification.userId(), retryNotification);
        } else {
            // 超过最大重试次数，进入死信队列 (DLQ)
            log.error("Notification reached max retries, moving to DLQ: {}", notification.notificationId());
            updateDbStatus(notification.notificationId(), "FAILED", errorMessage);
            kafkaTemplate.send("notification.dlq", notification.userId(), notification);
            redisTemplate.opsForValue().set(notification.idempotencyKey(), "FAILED", Duration.ofHours(24));

            Counter.builder("notification.dlq")
                    .tag("channel", notification.channel())
                    .tag("type", notification.type())
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void updateDbStatus(String id, String status, String errorMessage) {
        repository.findById(id).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setErrorMessage(errorMessage);
            if ("SENT".equals(status)) {
                entity.setSentAt(LocalDateTime.now());
            }
            entity.setUpdatedAt(LocalDateTime.now());
            repository.save(entity);
        });
    }
}