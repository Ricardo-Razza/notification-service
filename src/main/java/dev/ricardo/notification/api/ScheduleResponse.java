package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        String name,
        String cron,
        String timezone,
        NotificationChannel channel,
        String recipient,
        String template,
        Map<String, String> variables,
        boolean active,
        Instant startAt,
        Instant endAt,
        Instant nextRunAt,
        Instant lastRunAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScheduleResponse from(NotificationScheduleEntity entity) {
        return new ScheduleResponse(
                entity.getId(),
                entity.getName(),
                entity.getCron(),
                entity.getTimezone(),
                entity.getChannel(),
                entity.getRecipient(),
                entity.getTemplateCode(),
                entity.getPayload() != null ? entity.getPayload() : Map.of(),
                entity.isActive(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getNextRunAt(),
                entity.getLastRunAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
