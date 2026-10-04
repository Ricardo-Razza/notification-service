package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.application.NotificationPublisher;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.scheduler.enabled:true}")
    private boolean enabled;

    @lombok.Setter
    @Value("${app.scheduler.batch-size:100}")
    private int batchSize;

    @lombok.Setter
    @Value("${app.scheduler.max-lateness-seconds:3600}")
    private long maxLatenessSeconds;

    @Scheduled(fixedDelayString = "${app.scheduler.interval-ms:5000}")
    public void processScheduledNotifications() {
        if (!enabled) {
            return;
        }
        processScheduledBatch();
    }

    public int processScheduledBatch() {
        Instant now = Instant.now();
        Instant maxLatenessCutoff = now.minus(Duration.ofSeconds(maxLatenessSeconds));

        // Transação curta: bloqueia com SKIP LOCKED, avalia política de expiração, atualiza para QUEUED/FAILED
        List<UUID> notificationsToPublish = transactionTemplate.execute(status -> {
            List<NotificationEntity> batch = notificationRepository.findScheduledForUpdateSkipLocked(now, batchSize);
            if (batch.isEmpty()) {
                return List.of();
            }

            List<UUID> toPublish = new ArrayList<>();
            for (NotificationEntity entity : batch) {
                if (entity.getScheduledAt() != null && entity.getScheduledAt().isBefore(maxLatenessCutoff)) {
                    // Política de atraso: expirou além de max-lateness -> FAILED
                    entity.setStatus(NotificationStatus.FAILED);
                    entity.setLastError(String.format("Notification expired: scheduled for %s, which exceeded max-lateness threshold of %d seconds",
                            entity.getScheduledAt(), maxLatenessSeconds));
                    log.warn("Notification {} expired past max-lateness. Marked as FAILED.", entity.getId());
                } else {
                    entity.setStatus(NotificationStatus.QUEUED);
                    toPublish.add(entity.getId());
                }
            }
            notificationRepository.saveAll(batch);
            return toPublish;
        });

        if (notificationsToPublish == null || notificationsToPublish.isEmpty()) {
            return 0;
        }

        // Publicação na fila acontece DEPOIS do commit da transação (fora da transação de banco)
        int publishedCount = 0;
        for (UUID notificationId : notificationsToPublish) {
            try {
                notificationPublisher.publish(notificationId);
                publishedCount++;
            } catch (Exception ex) {
                // Se falhar na publicação, a notificação permanece como QUEUED
                // num estado recuperável pelo retry manual ou rotina de reconciliação
                log.error("Failed to publish scheduled notification {} to RabbitMQ. Left in recoverable QUEUED status.", notificationId, ex);
            }
        }

        if (publishedCount > 0) {
            log.info("Scheduler batch processed: {} notifications released to queue", publishedCount);
        }
        return publishedCount;
    }
}
