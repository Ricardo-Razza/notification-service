package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.domain.InvalidScheduleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTimeCalculatorTest {

    private ScheduleTimeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ScheduleTimeCalculator();
    }

    @Test
    @DisplayName("Validação do cron: aceita 6 campos do Spring e rejeita formatos inválidos ou 5 campos")
    void shouldValidateCronExpressionsCorrectly() {
        assertThat(calculator.parseAndValidateCron("0 0 9 * * *")).isNotNull();
        assertThat(calculator.parseAndValidateCron("0 * * * * *")).isNotNull();

        // 5 campos (formato Linux padrão) deve ser rejeitado pelo CronExpression do Spring
        assertThatThrownBy(() -> calculator.parseAndValidateCron("* * * * *"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("Invalid cron expression");

        // Expressão malformada
        assertThatThrownBy(() -> calculator.parseAndValidateCron("invalid cron"))
                .isInstanceOf(InvalidScheduleException.class);

        // Vazio ou nulo
        assertThatThrownBy(() -> calculator.parseAndValidateCron(""))
                .isInstanceOf(InvalidScheduleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/Sao_Paulo", "America/New_York", "Europe/London", "Asia/Tokyo", "UTC"})
    @DisplayName("Validação de timezone: aceita fusos horários válidos IANA")
    void shouldAcceptValidTimezones(String timezone) {
        assertThat(calculator.parseAndValidateTimezone(timezone)).isEqualTo(ZoneId.of(timezone));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Invalid/Timezone", "SaoPaulo", "GMT+99:00", ""})
    @DisplayName("Validação de timezone: rejeita fusos horários inválidos com 400 Bad Request")
    void shouldRejectInvalidTimezones(String timezone) {
        assertThatThrownBy(() -> calculator.parseAndValidateTimezone(timezone))
                .isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    @DisplayName("Cálculo em fusos diferentes: calcula próximo horário com precisão no fuso de São Paulo (UTC-3)")
    void shouldCalculateNextRunInSaoPauloTimezone() {
        // Base: 2026-10-04 10:00:00 UTC (07:00:00 em São Paulo)
        // Cron: todo dia às 09:00 em São Paulo -> deve ser 2026-10-04 12:00:00 UTC
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        Instant next = calculator.calculateNextRun("0 0 9 * * *", "America/Sao_Paulo", base);

        assertThat(next).isNotNull();
        assertThat(next).isEqualTo(Instant.parse("2026-10-04T12:00:00Z"));
    }

    @Test
    @DisplayName("Cálculo em fusos diferentes: calcula próximo horário com precisão em Tóquio (UTC+9)")
    void shouldCalculateNextRunInTokyoTimezone() {
        // Base: 2026-10-04 10:00:00 UTC (19:00:00 em Tóquio)
        // Cron: todo dia às 09:00 em Tóquio -> deve ser 2026-10-05 00:00:00 UTC
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        Instant next = calculator.calculateNextRun("0 0 9 * * *", "Asia/Tokyo", base);

        assertThat(next).isNotNull();
        assertThat(next).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    @DisplayName("Cuidado com horário de verão: transição DST em Nova Iorque (março de 2026)")
    void shouldHandleDaylightSavingTimeTransitionInNewYork() {
        // No domingo, 8 de março de 2026, às 02:00 os relógios em Nova Iorque adiantam 1h para 03:00 (EDT, UTC-4)
        // Antes do DST (7 de março): UTC-5 -> 14:00 Nova Iorque = 19:00 UTC
        // Depois do DST (9 de março): UTC-4 -> 14:00 Nova Iorque = 18:00 UTC
        Instant baseBeforeDst = Instant.parse("2026-03-07T12:00:00Z");
        Instant nextBeforeDst = calculator.calculateNextRun("0 0 14 * * *", "America/New_York", baseBeforeDst);
        assertThat(nextBeforeDst).isEqualTo(Instant.parse("2026-03-07T19:00:00Z"));

        Instant baseAfterDst = Instant.parse("2026-03-08T20:00:00Z");
        Instant nextAfterDst = calculator.calculateNextRun("0 0 14 * * *", "America/New_York", baseAfterDst);
        assertThat(nextAfterDst).isEqualTo(Instant.parse("2026-03-09T18:00:00Z"));
    }

    @Test
    @DisplayName("calculateInitialNextRun respeita startAt no futuro e endAt")
    void shouldRespectStartAtAndEndAtInInitialCalculation() {
        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        Instant startAt = Instant.parse("2026-10-10T00:00:00Z");
        Instant endAt = Instant.parse("2026-10-15T00:00:00Z");

        // Cron: todo dia às 09:00 UTC
        // Próximo disparo deve ser 2026-10-10T09:00:00Z (primeiro após startAt)
        Instant next = calculator.calculateInitialNextRun("0 0 9 * * *", "UTC", startAt, endAt, now);
        assertThat(next).isEqualTo(Instant.parse("2026-10-10T09:00:00Z"));

        // Se endAt for anterior ao primeiro disparo, retorna nulo
        Instant strictEndAt = Instant.parse("2026-10-10T08:00:00Z");
        Instant expiredNext = calculator.calculateInitialNextRun("0 0 9 * * *", "UTC", startAt, strictEndAt, now);
        assertThat(expiredNext).isNull();
    }
}
