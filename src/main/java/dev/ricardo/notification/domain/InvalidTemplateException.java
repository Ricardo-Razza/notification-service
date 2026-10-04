package dev.ricardo.notification.domain;

public class InvalidTemplateException extends RuntimeException {
    public InvalidTemplateException(String message) {
        super(message);
    }
}
