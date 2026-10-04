package dev.ricardo.notification.domain;

public class NotificationCancelConflictException extends RuntimeException {
    public NotificationCancelConflictException(String message) {
        super(message);
    }
}
