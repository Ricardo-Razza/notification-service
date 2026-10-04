package dev.ricardo.notification.domain;

public class InvalidAdminTokenException extends RuntimeException {
    public InvalidAdminTokenException(String message) {
        super(message);
    }
}
