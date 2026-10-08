package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.application.NotificationPublisher;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j @Component @RequiredArgsConstructor
public class NotificationReconciler {
    private final NotificationRepository notifications;
    private final NotificationAttemptRepository attempts;
    private final NotificationPublisher publisher;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    @Value("${app.reconciler.enabled:true}") private boolean enabled;
    @Value("${app.reconciler.pending-seconds:120}") private long pendingSeconds;
    @Value("${app.reconciler.queued-seconds:600}") private long queuedSeconds;
    @Value("${app.reconciler.processing-seconds:300}") private long processingSeconds;
    @Value("${app.reconciler.batch-size:100}") private int batchSize;
    @Value("${app.reconciler.max-recoveries:5}") private int maxRecoveries;
    private record Recovery(UUID id, String reason) {}

    @Scheduled(fixedDelayString="${app.reconciler.interval-ms:60000}", initialDelayString="${app.reconciler.interval-ms:60000}")
    public void scheduled() { if (enabled) reconcile(); }

    public int reconcile() {
        Instant now = Instant.now();
        List<Recovery> recovered = transactions.execute(tx -> {
            List<Recovery> result = new ArrayList<>();
            var rows = notifications.findStalledForUpdate(now.minusSeconds(pendingSeconds), now.minusSeconds(queuedSeconds),
                    now.minusSeconds(processingSeconds), batchSize);
            for (var n : rows) {
                String reason = n.getStatus().name().toLowerCase(java.util.Locale.ROOT);
                if (n.getStatus() == NotificationStatus.PROCESSING) {
                    n.setAttempts(n.getAttempts() + 1);
                    n.setLastError("Worker timeout");
                    attempts.save(NotificationAttemptEntity.builder().id(UUID.randomUUID()).notificationId(n.getId()).attemptNumber(n.getAttempts())
                            .status(AttemptStatus.FAILURE).errorMessage("Worker timeout").startedAt(n.getUpdatedAt()).finishedAt(now).build());
                    notifications.saveAndFlush(n);
                }
                if (n.getRecoveryCount() >= maxRecoveries) {
                    n.setStatus(NotificationStatus.FAILED); n.setLastError("Recovery limit exceeded: " + reason);
                    notifications.save(n); continue;
                }
                if (notifications.recover(n.getId().toString(), n.getStatus().name(), n.getRecoveryCount(), now) == 1) {
                    result.add(new Recovery(n.getId(), reason));
                }
            }
            return result;
        });
        int count = 0;
        for (var item : recovered == null ? List.<Recovery>of() : recovered) {
            metrics.counter("notifications.recovered", "type", item.reason()).increment();
            log.info("Recovering notification {} reason={}", item.id(), item.reason());
            try { publisher.publish(item.id()); count++; }
            catch (Exception ex) { log.warn("Recovery publication failed notification={} type={}", item.id(), ex.getClass().getSimpleName()); }
        }
        return count;
    }
}
