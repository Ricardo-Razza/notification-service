package dev.ricardo.notification.api;

public record SendNotificationResult(
        NotificationResponse response,
        boolean replayed
) {}
