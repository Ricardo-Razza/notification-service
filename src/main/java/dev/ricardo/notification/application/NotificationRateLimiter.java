package dev.ricardo.notification.application;

import dev.ricardo.notification.domain.RateLimitException;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component @RequiredArgsConstructor
public class NotificationRateLimiter {
    private final ApplicationRepository applications;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();
    @Value("${app.limits.rate-per-minute:100}") private int defaultLimit = 100;
    private Clock clock = Clock.systemUTC();
    private static class Window { long minute; int used; Window(long minute) { this.minute = minute; } }
    void setClock(Clock clock) { this.clock = clock; }
    public void reserve(UUID applicationId, int count) {
        if (applicationId == null) return;
        Integer configured = applications.findById(applicationId).map(a -> a.getRateLimitPerMinute()).orElse(null);
        int limit = configured == null ? defaultLimit : configured;
        long seconds = clock.instant().getEpochSecond(); long minute = seconds / 60;
        // compute serializa reserva e expiração por aplicação, inclusive contra limpeza concorrente.
        windows.compute(applicationId, (id, previous) -> {
            Window window = previous == null || previous.minute != minute ? new Window(minute) : previous;
            if (count < 1 || count > limit - window.used) {
                metrics.counter("notifications.limit.rejected", "type", "rate").increment();
                throw new RateLimitException(60 - Math.floorMod(seconds, 60));
            }
            window.used += count; return window;
        });
    }
    @Scheduled(fixedDelay = 120000)
    public void clean() {
        long before = clock.instant().getEpochSecond() / 60 - 1;
        windows.forEach((id, ignored) -> windows.computeIfPresent(id, (key, window) -> window.minute < before ? null : window));
    }
}
