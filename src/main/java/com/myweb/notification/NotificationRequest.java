package com.myweb.notification;

import java.util.Map;

public record NotificationRequest(
        String userId,
        String type,
        String channel,
        String templateId,
        Map<String, Object> data,
        String idempotencyKey
) {}
