package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.Duration;

@Slf4j @Component @RequiredArgsConstructor
public class NotificationRetention {
    private final NotificationRepository notifications;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    @Value("${app.retention.enabled:true}") private boolean enabled;
    @Value("${app.retention.delete-days:90}") private int deleteDays;
    @Value("${app.retention.anonymize-days:30}") private int anonymizeDays;
    @Value("${app.retention.batch-size:1000}") private int batchSize;
    @Value("${app.retention.pause-ms:200}") private long pauseMs;
    public record Result(int deleted, int anonymized) {}
    @Scheduled(cron="${app.retention.cron:0 0 3 * * *}", zone="UTC")
    public void scheduled() { if (enabled) run(); }
    public Result run() {
        int deleted = deleteDays <= 0 ? 0 : process(true, Instant.now().minus(Duration.ofDays(deleteDays)));
        int anonymized = anonymizeDays <= 0 ? 0 : process(false, Instant.now().minus(Duration.ofDays(anonymizeDays)));
        metrics.counter("notifications.retention", "action", "deleted").increment(deleted);
        metrics.counter("notifications.retention", "action", "anonymized").increment(anonymized);
        log.info("Retention deleted={} anonymized={}", deleted, anonymized);
        return new Result(deleted, anonymized);
    }
    private int process(boolean delete, Instant cutoff) {
        int total = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Integer count = transactions.execute(tx -> {
                var rows = delete ? notifications.findExpiredFinal(cutoff, batchSize) : notifications.findFinalToAnonymize(cutoff, batchSize);
                if (delete) notifications.deleteAllInBatch(rows);
                else for (var n : rows) {
                    n.setPayload(null); n.setRecipient(mask(n.getRecipient())); n.setAnonymizedAt(Instant.now()); notifications.save(n);
                }
                return rows.size();
            });
            if (count == null || count == 0) break;
            total += count;
            if (count < batchSize) break;
            try { Thread.sleep(pauseMs); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
        }
        return total;
    }
    public static String mask(String recipient) {
        int at = recipient.indexOf('@');
        return at > 0 ? recipient.substring(0, 1) + "***" + recipient.substring(at) : "***";
    }
}
