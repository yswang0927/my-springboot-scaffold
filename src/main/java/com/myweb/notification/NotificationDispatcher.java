package com.myweb.notification;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一路由分发器
 */
@Component
public class NotificationDispatcher {

    private final Map<String, NotificationProvider> providerMap;

    public NotificationDispatcher(List<NotificationProvider> providers) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(NotificationProvider::getChannel, Function.identity()));
    }

    public void dispatch(Notification notification) throws Exception {
        NotificationProvider provider = providerMap.get(notification.channel());
        if (provider == null) {
            throw new IllegalArgumentException("No provider found for channel: " + notification.channel());
        }
        provider.send(notification);
    }
}
