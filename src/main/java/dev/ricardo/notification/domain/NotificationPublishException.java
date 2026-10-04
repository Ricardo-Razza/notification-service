package dev.ricardo.notification.domain;

public class NotificationPublishException extends RuntimeException {

    public NotificationPublishException(String message) {
        super(message);
    }

    public NotificationPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
