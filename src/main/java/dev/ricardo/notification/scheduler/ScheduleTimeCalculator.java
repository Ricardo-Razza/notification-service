package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.domain.InvalidScheduleException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class ScheduleTimeCalculator {

    private final java.util.concurrent.ConcurrentHashMap<String,CronExpression> validatedCrons=new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${app.limits.minimum-cron-seconds:60}")
    private long minimumCronSeconds = 60;

    public CronExpression parseAndValidateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new InvalidScheduleException("Cron expression cannot be empty");
        }
        String cacheKey=minimumCronSeconds+":"+cron.trim();
        CronExpression cached=validatedCrons.get(cacheKey);if(cached!=null)return cached;
        try {
            CronExpression expression = CronExpression.parse(cron.trim());
            // O primeiro campo controla segundos: com vários segundos, a frequência pode exceder o limite
            // em um minuto futuro mesmo quando as próximas ocorrências caem em dias distintos.
            String seconds = cron.trim().split("\\s+")[0];
            if (minimumCronSeconds >= 60 && !seconds.matches("[0-5]?[0-9]")) {
                throw new InvalidScheduleException("Cron frequency must be at least " + minimumCronSeconds + " seconds");
            }
            ZonedDateTime cursor = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
            for (int i = 0; i < 60; i++) {
                ZonedDateTime next = expression.next(cursor);
                if (next == null) break;
                if (i > 0 && java.time.Duration.between(cursor, next).getSeconds() < minimumCronSeconds)
                    throw new InvalidScheduleException("Cron frequency must be at least " + minimumCronSeconds + " seconds");
                cursor = next;
            }
            if(validatedCrons.size()>=256)validatedCrons.clear();
            validatedCrons.put(cacheKey,expression);
            return expression;
        } catch (IllegalArgumentException ex) {
            throw new InvalidScheduleException("Invalid cron expression: " + ex.getMessage());
        }
    }

    public ZoneId parseAndValidateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new InvalidScheduleException("Timezone cannot be empty");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException | IllegalArgumentException ex) {
            throw new InvalidScheduleException("Invalid timezone: " + timezone);
        }
    }

    public Instant calculateNextRun(String cron, String timezone, Instant baseInstant) {
        CronExpression expression = parseAndValidateCron(cron);
        ZoneId zoneId = parseAndValidateTimezone(timezone);
        ZonedDateTime zdt = baseInstant.atZone(zoneId);
        ZonedDateTime nextZdt = expression.next(zdt);
        return nextZdt != null ? nextZdt.toInstant().truncatedTo(ChronoUnit.MICROS) : null;
    }

    public Instant calculateInitialNextRun(String cron, String timezone, Instant startAt, Instant endAt, Instant now) {
        Instant baseInstant = (startAt != null && startAt.isAfter(now))
                ? startAt.minus(1, ChronoUnit.MILLIS)
                : now;

        Instant nextRun = calculateNextRun(cron, timezone, baseInstant);

        if (endAt != null && (nextRun == null || nextRun.isAfter(endAt))) {
            return null;
        }
        return nextRun;
    }
}
