package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationStatus status,
        NotificationChannel channel,
        String recipient,
        String template,
        int attempts,
        Instant scheduledAt,
        Instant createdAt,
        Instant sentAt,
        String lastError,
        List<NotificationAttemptResponse> attemptsHistory
) {
    public NotificationResponse(
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
        this(id, status, channel, recipient, template, attempts, null, createdAt, sentAt, lastError, List.of());
    }

    public NotificationResponse(
            UUID id,
            NotificationStatus status,
            NotificationChannel channel,
            String recipient,
            String template,
            int attempts,
            Instant createdAt,
            Instant sentAt,
            String lastError,
            List<NotificationAttemptResponse> attemptsHistory
    ) {
        this(id, status, channel, recipient, template, attempts, null, createdAt, sentAt, lastError, attemptsHistory);
    }

    public static NotificationResponse from(NotificationEntity entity) {
        return from(entity, List.of());
    }

    public static NotificationResponse from(NotificationEntity entity, List<NotificationAttemptResponse> attemptsHistory) {
        return new NotificationResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getChannel(),
                entity.getRecipient(),
                entity.getTemplateCode(),
                entity.getAttempts(),
                entity.getScheduledAt(),
                entity.getCreatedAt(),
                entity.getSentAt(),
                entity.getLastError(),
                attemptsHistory != null ? attemptsHistory : List.of()
        );
    }
}
