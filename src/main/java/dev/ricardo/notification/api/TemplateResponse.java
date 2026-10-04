package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;

import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String code,
        NotificationChannel channel,
        String subject,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
    public static TemplateResponse from(TemplateEntity entity) {
        return new TemplateResponse(
                entity.getId(),
                entity.getCode(),
                entity.getChannel(),
                entity.getSubject(),
                entity.getContent(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
