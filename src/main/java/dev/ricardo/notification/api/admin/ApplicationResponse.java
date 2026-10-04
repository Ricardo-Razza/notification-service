package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;

import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(
        UUID id,
        String name,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ApplicationResponse from(ApplicationEntity entity) {
        return new ApplicationResponse(
                entity.getId(),
                entity.getName(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
