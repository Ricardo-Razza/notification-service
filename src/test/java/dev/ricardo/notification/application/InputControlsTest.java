package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import dev.ricardo.notification.scheduler.ScheduleTimeCalculator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InputControlsTest {
    @Test void variableAndTemplateLimitsRejectOversizedData() {
        var limits=new InputLimits(); var variables=new HashMap<String,String>();
        for(int i=0;i<51;i++) variables.put("v"+i,"x");
        assertThatThrownBy(() -> limits.variables(variables)).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> limits.variables(Map.of("x".repeat(65),"value"))).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> limits.variables(Map.of("x","y".repeat(2001)))).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> limits.template("subject","x".repeat(50001))).isInstanceOf(InvalidTemplateException.class);
        assertThatThrownBy(() -> new ScheduleTimeCalculator().parseAndValidateCron("* * * * * *")).isInstanceOf(InvalidScheduleException.class);
    }
    @Test void hashingNormalizesVariableOrderAndScheduledOffset() {
        var a=new CreateNotificationRequest("a@example.com",NotificationChannel.EMAIL,"WELCOME",Map.of("b","2","a","1"),"2026-12-01T10:00:00-03:00");
        var b=new CreateNotificationRequest("a@example.com",NotificationChannel.EMAIL,"WELCOME",Map.of("a","1","b","2"),"2026-12-01T13:00:00Z");
        assertThat(NotificationRequestHasher.hash(a)).isEqualTo(NotificationRequestHasher.hash(b));
        assertThat(NotificationRequestHasher.hash(new CreateNotificationRequest("a@example.com",NotificationChannel.EMAIL,"WELCOME",Map.of("a","2","b","1"),b.scheduledAt())))
                .isNotEqualTo(NotificationRequestHasher.hash(a));
    }
    @Test void minuteWindowResetsAndBatchCountsNotifications() {
        var repo=mock(ApplicationRepository.class); var metrics=new SimpleMeterRegistry();
        var id=UUID.randomUUID(); when(repo.findById(id)).thenReturn(Optional.of(ApplicationEntity.builder().rateLimitPerMinute(3).build()));
        var limiter=new NotificationRateLimiter(repo,metrics);
        limiter.setClock(Clock.fixed(Instant.parse("2026-10-01T12:00:20Z"),ZoneOffset.UTC));
        limiter.reserve(id,3);
        assertThatThrownBy(() -> limiter.reserve(id,1)).isInstanceOfSatisfying(RateLimitException.class, ex -> assertThat(ex.retryAfter()).isEqualTo(40));
        limiter.setClock(Clock.fixed(Instant.parse("2026-10-01T12:01:00Z"),ZoneOffset.UTC)); limiter.reserve(id,1);
        assertThat(metrics.counter("notifications.limit.rejected","type","rate").count()).isEqualTo(1);
    }
}
