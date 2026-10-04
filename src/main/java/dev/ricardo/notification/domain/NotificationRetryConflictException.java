package dev.ricardo.notification.domain;

public class NotificationRetryConflictException extends RuntimeException {
    public NotificationRetryConflictException(String message) {
        super(message);
    }
}
