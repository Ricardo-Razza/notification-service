package dev.ricardo.notification.api.admin;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleEntity;
import dev.ricardo.notification.scheduler.NotificationRetention;
import java.time.Instant;
import java.util.*;
public record AdminScheduleResponse(UUID id,UUID applicationId,String name,String cron,String timezone,NotificationChannel channel,String recipient,String template,
        Map<String,String> variables,boolean active,Instant startAt,Instant endAt,Instant nextRunAt,Instant lastRunAt,Instant createdAt,Instant updatedAt) {
    public static AdminScheduleResponse from(NotificationScheduleEntity s,boolean viewer) {return new AdminScheduleResponse(s.getId(),s.getApplicationId(),s.getName(),s.getCron(),s.getTimezone(),s.getChannel(),
            viewer?NotificationRetention.mask(s.getRecipient()):s.getRecipient(),s.getTemplateCode(),s.getPayload()==null?Map.of():s.getPayload(),s.isActive(),s.getStartAt(),s.getEndAt(),s.getNextRunAt(),s.getLastRunAt(),s.getCreatedAt(),s.getUpdatedAt());}
}
