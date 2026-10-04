package dev.ricardo.notification.domain;

public class DuplicateTemplateException extends RuntimeException {
    public DuplicateTemplateException(String code, NotificationChannel channel) {
        super("Template already exists for code '" + code + "' and channel '" + channel + "'");
    }
}
