package dev.ricardo.notification.domain;

public class GlobalTemplateReadOnlyException extends RuntimeException {
    public GlobalTemplateReadOnlyException() {
        super("Global templates are read-only for applications");
    }
}
