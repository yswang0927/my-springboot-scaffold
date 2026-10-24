package com.myweb.notification;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_notifications", indexes = {
        @Index(name = "idx_scheduled_time", columnList = "scheduledTime"),
        @Index(name = "idx_processed", columnList = "processed")
})
public class ScheduledNotificationEntity {
    @Id
    private String id;
    private LocalDateTime scheduledTime;
    @Lob
    @Column(length = 2000)
    private String notificationJson;
    private boolean processed = false;
    private LocalDateTime createdAt = LocalDateTime.now();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(LocalDateTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public String getNotificationJson() {
        return notificationJson;
    }

    public void setNotificationJson(String notificationJson) {
        this.notificationJson = notificationJson;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}