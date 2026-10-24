package com.myweb.notification;

import java.util.Map;

public record Notification(
        String notificationId,
        String userId,
        String type,           // OTP, MARKETING, SECURITY
        String channel,        // PUSH, EMAIL, SMS, IN_APP
        String templateId,
        Map<String, Object> data,
        String idempotencyKey,
        int retryCount         // 当前重试次数
) {}