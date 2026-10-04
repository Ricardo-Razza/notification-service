package dev.ricardo.notification.domain;

import java.util.UUID;

public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(UUID id) {
        super("Notification not found with id: " + id);
    }

    public NotificationNotFoundException(String message) {
        super(message);
    }
}
