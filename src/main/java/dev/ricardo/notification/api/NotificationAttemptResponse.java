package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.AttemptStatus;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptEntity;

import java.time.Instant;
import java.util.UUID;

public record NotificationAttemptResponse(
        UUID id,
        int attemptNumber,
        AttemptStatus status,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
    public static NotificationAttemptResponse from(NotificationAttemptEntity entity) {
        return new NotificationAttemptResponse(
                entity.getId(),
                entity.getAttemptNumber(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }
}
