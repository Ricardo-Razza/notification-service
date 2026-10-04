package dev.ricardo.notification.domain;

public class AdminDisabledException extends RuntimeException {
    public AdminDisabledException(String message) {
        super(message);
    }
}
