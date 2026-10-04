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

    public CronExpression parseAndValidateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new InvalidScheduleException("Cron expression cannot be empty");
        }
        try {
            return CronExpression.parse(cron.trim());
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
