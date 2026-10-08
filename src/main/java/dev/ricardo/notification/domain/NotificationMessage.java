package dev.ricardo.notification.domain;

import java.util.UUID;

public record NotificationMessage(UUID notificationId, String requestId) {
    public NotificationMessage(UUID notificationId) { this(notificationId, null); }
}
