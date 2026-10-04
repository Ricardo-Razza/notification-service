package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationStatus status,
        NotificationChannel channel,
        String recipient,
        String template,
        int attempts,
        Instant createdAt,
        Instant sentAt,
        String lastError
) {
    public static NotificationResponse from(NotificationEntity entity) {
        return new NotificationResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getChannel(),
                entity.getRecipient(),
                entity.getTemplateCode(),
                entity.getAttempts(),
                entity.getCreatedAt(),
                entity.getSentAt(),
                entity.getLastError()
        );
    }
}
