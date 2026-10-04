package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;

import java.time.Instant;
import java.util.UUID;

public record ApplicationCreatedResponse(
        UUID id,
        String name,
        String apiKey,
        boolean active,
        Instant createdAt
) {
    public static ApplicationCreatedResponse of(ApplicationEntity entity, String apiKey) {
        return new ApplicationCreatedResponse(
                entity.getId(),
                entity.getName(),
                apiKey,
                entity.isActive(),
                entity.getCreatedAt()
        );
    }
}
