package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.api.NotificationAttemptResponse;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.scheduler.NotificationRetention;
import java.time.Instant;
import java.util.*;
public record AdminNotificationResponse(UUID id,UUID applicationId,UUID scheduleId,String origin,NotificationStatus status,
        NotificationChannel channel,String recipient,String template,Map<String,String> variables,int attempts,Instant scheduledAt,
        Instant createdAt,Instant sentAt,String lastError,List<NotificationAttemptResponse> attemptsHistory) {
    public static AdminNotificationResponse from(NotificationEntity n,boolean viewer,List<NotificationAttemptResponse> attempts) {
        return new AdminNotificationResponse(n.getId(),n.getApplicationId(),n.getScheduleId(),n.getOrigin(),n.getStatus(),n.getChannel(),
                viewer?NotificationRetention.mask(n.getRecipient()):n.getRecipient(),n.getTemplateCode(),n.getPayload()==null?Map.of():n.getPayload(),n.getAttempts(),
                n.getScheduledAt(),n.getCreatedAt(),n.getSentAt(),n.getLastError(),attempts);
    }
}
