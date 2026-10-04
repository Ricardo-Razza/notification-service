package dev.ricardo.notification.domain;

import java.util.UUID;

public class ApplicationNotFoundException extends RuntimeException {
    public ApplicationNotFoundException(UUID id) {
        super(String.format("Application not found with id: %s", id));
    }
}
