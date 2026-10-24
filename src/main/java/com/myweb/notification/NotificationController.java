package com.myweb.notification;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<String> sendNotification(@RequestBody NotificationRequest request) {
        notificationService.send(request);
        return ResponseEntity.accepted().body("Notification request accepted and queued.");
    }

    @PostMapping("/schedule")
    public ResponseEntity<String> scheduleNotification(
            @RequestBody NotificationRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime scheduledTime) {
        notificationService.schedule(request, scheduledTime);
        return ResponseEntity.accepted().body("Notification scheduled for " + scheduledTime);
    }
}