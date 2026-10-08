package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.application.NotificationPublisher;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationScheduleRepository scheduleRepository;
    private final NotificationPublisher notificationPublisher;
    private final ScheduleTimeCalculator scheduleTimeCalculator;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.scheduler.enabled:true}")
    private boolean enabled;

    @lombok.Setter
    @Value("${app.scheduler.batch-size:100}")
    private int batchSize;

    @lombok.Setter
    @Value("${app.scheduler.max-lateness-seconds:3600}")
    private long maxLatenessSeconds;

    @lombok.Setter
    @Value("${app.scheduler.missed-occurrence-policy:SKIP_AND_GENERATE_LATEST}")
    private MissedOccurrencePolicy missedOccurrencePolicy = MissedOccurrencePolicy.SKIP_AND_GENERATE_LATEST;

    @Scheduled(fixedDelayString = "${app.scheduler.interval-ms:5000}")
    public void processScheduledNotifications() {
        if (!enabled) {
            return;
        }
        processScheduledBatch();
        processRecurringSchedulesBatch();
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
                log.error("Failed to publish scheduled notification {} to RabbitMQ. Left in recoverable QUEUED status.", notificationId, ex);
            }
        }

        if (publishedCount > 0) {
            log.info("Scheduler batch processed: {} notifications released to queue", publishedCount);
        }
        return publishedCount;
    }

    public int processRecurringSchedulesBatch() {
        Instant now = Instant.now();

        // Transação curta com SKIP LOCKED para agendamentos recorrentes
        List<UUID> notificationsToPublish = transactionTemplate.execute(status -> {
            List<NotificationScheduleEntity> batch = scheduleRepository.findActiveForUpdateSkipLocked(now, batchSize);
            if (batch.isEmpty()) {
                return List.of();
            }

            List<UUID> toPublish = new ArrayList<>();
            for (NotificationScheduleEntity schedule : batch) {
                // 1. Respeita startAt se for no futuro
                if (schedule.getStartAt() != null && now.isBefore(schedule.getStartAt())) {
                    Instant next = scheduleTimeCalculator.calculateNextRun(
                            schedule.getCron(), schedule.getTimezone(), schedule.getStartAt().minusMillis(1));
                    schedule.setNextRunAt(next);
                    scheduleRepository.save(schedule);
                    continue;
                }

                // 2. Respeita endAt se já venceu
                if (schedule.getEndAt() != null && schedule.getNextRunAt().isAfter(schedule.getEndAt())) {
                    schedule.setActive(false);
                    schedule.setNextRunAt(null);
                    scheduleRepository.save(schedule);
                    log.info("Schedule {} ('{}') has reached endAt {}. Deactivated.", schedule.getId(), schedule.getName(), schedule.getEndAt());
                    continue;
                }

                // 3. Identifica ocorrências devidas até o instante atual
                List<Instant> dueOccurrences = new ArrayList<>();
                int occurrenceCount=0;
                Instant occurrence = schedule.getNextRunAt();
                while (occurrence != null && !occurrence.isAfter(now)) {
                    if (schedule.getEndAt() != null && occurrence.isAfter(schedule.getEndAt())) {
                        break;
                    }
                    occurrenceCount++;
                    if(missedOccurrencePolicy==MissedOccurrencePolicy.SKIP_AND_GENERATE_LATEST)dueOccurrences.clear();
                    dueOccurrences.add(occurrence);
                    occurrence = scheduleTimeCalculator.calculateNextRun(schedule.getCron(), schedule.getTimezone(), occurrence);
                }

                if (dueOccurrences.isEmpty()) {
                    Instant next = scheduleTimeCalculator.calculateNextRun(schedule.getCron(), schedule.getTimezone(), now);
                    schedule.setNextRunAt(next);
                    scheduleRepository.save(schedule);
                    continue;
                }

                // 4. Política de ocorrências perdidas
                if (occurrenceCount > 1 && missedOccurrencePolicy == MissedOccurrencePolicy.SKIP_AND_GENERATE_LATEST) {
                    int skipped = occurrenceCount - 1;
                    Instant latest = dueOccurrences.get(dueOccurrences.size() - 1);
                    log.warn("Missed {} occurrences for schedule {} ('{}'). Skipping and generating only the latest occurrence at {}",
                            skipped, schedule.getId(), schedule.getName(), latest);
                    dueOccurrences = List.of(latest);
                }

                // 5. Gera as notificações para as ocorrências selecionadas
                ZoneId zoneId = scheduleTimeCalculator.parseAndValidateTimezone(schedule.getTimezone());
                for (Instant occurrenceInstant : dueOccurrences) {
                    String idempotencyKey = String.format("schedule-%s-%s", schedule.getId(), occurrenceInstant);

                    // Unicidade garantida por (application_id, idempotency_key)
                    boolean alreadyExists = notificationRepository
                            .findByApplicationIdAndIdempotencyKey(schedule.getApplicationId(), idempotencyKey)
                            .isPresent();
                    if (alreadyExists) {
                        log.info("Notification for schedule {} occurrence {} already exists. Skipping.", schedule.getId(), occurrenceInstant);
                        continue;
                    }

                    // Resolução dinâmica de variáveis de sistema no fuso do agendamento
                    Map<String, String> resolvedVariables = new HashMap<>();
                    if (schedule.getPayload() != null) {
                        resolvedVariables.putAll(schedule.getPayload());
                    }
                    ZonedDateTime occurrenceZdt = occurrenceInstant.atZone(zoneId);
                    resolvedVariables.put("today", occurrenceZdt.toLocalDate().toString());
                    resolvedVariables.put("now", occurrenceZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

                    NotificationEntity notification = NotificationEntity.builder()
                            .applicationId(schedule.getApplicationId())
                            .scheduleId(schedule.getId())
                            .origin("SCHEDULE")
                            .channel(schedule.getChannel())
                            .recipient(schedule.getRecipient())
                            .templateCode(schedule.getTemplateCode())
                            .payload(resolvedVariables)
                            .status(NotificationStatus.QUEUED)
                            .attempts(0)
                            .scheduledAt(occurrenceInstant)
                            .idempotencyKey(idempotencyKey)
                            .build();

                    NotificationEntity savedNotification = notificationRepository.save(notification);
                    toPublish.add(savedNotification.getId());
                }

                // 6. Atualiza last_run_at e next_run_at
                Instant latestProcessed = dueOccurrences.get(dueOccurrences.size() - 1);
                schedule.setLastRunAt(latestProcessed);

                Instant nextRun = scheduleTimeCalculator.calculateNextRun(schedule.getCron(), schedule.getTimezone(), latestProcessed);
                if (schedule.getEndAt() != null && (nextRun == null || nextRun.isAfter(schedule.getEndAt()))) {
                    schedule.setActive(false);
                    schedule.setNextRunAt(null);
                    log.info("Schedule {} ('{}') reached endAt {}. Deactivated.", schedule.getId(), schedule.getName(), schedule.getEndAt());
                } else {
                    schedule.setNextRunAt(nextRun);
                }
                scheduleRepository.save(schedule);
            }
            return toPublish;
        });

        if (notificationsToPublish == null || notificationsToPublish.isEmpty()) {
            return 0;
        }

        // Publicação na fila RabbitMQ fora da transação
        int publishedCount = 0;
        for (UUID notificationId : notificationsToPublish) {
            try {
                notificationPublisher.publish(notificationId);
                publishedCount++;
            } catch (Exception ex) {
                log.error("Failed to publish recurring notification {} to RabbitMQ. Left in recoverable QUEUED status.", notificationId, ex);
            }
        }

        if (publishedCount > 0) {
            log.info("Recurring schedules batch processed: {} notifications released to queue", publishedCount);
        }
        return publishedCount;
    }
}
