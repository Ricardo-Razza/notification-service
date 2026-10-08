package dev.ricardo.notification.api.admin;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import java.time.Instant;
import java.util.UUID;
public record AdminTemplateResponse(UUID id,UUID applicationId,String code,NotificationChannel channel,String subject,String content,Instant createdAt,Instant updatedAt) {
    public static AdminTemplateResponse from(TemplateEntity t) {return new AdminTemplateResponse(t.getId(),t.getApplicationId(),t.getCode(),t.getChannel(),t.getSubject(),t.getContent(),t.getCreatedAt(),t.getUpdatedAt());}
}
