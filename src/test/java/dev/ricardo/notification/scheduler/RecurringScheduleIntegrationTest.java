package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.application.NotificationPublisher;
import dev.ricardo.notification.application.ScheduleService;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class RecurringScheduleIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notification_test")
            .withUsername("notification")
            .withPassword("notification");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("management.health.mail.enabled", () -> "false");

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");

        registry.add("app.scheduler.enabled", () -> "false");
    }

    @Autowired
    private NotificationScheduler notificationScheduler;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private NotificationScheduleRepository scheduleRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @MockitoSpyBean
    private NotificationPublisher notificationPublisher;

    private ApplicationEntity testApp;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        scheduleRepository.deleteAll();
        clearInvocations(notificationPublisher);

        testApp = applicationRepository.save(ApplicationEntity.builder()
                .name("Recurring Test App " + UUID.randomUUID())
                .apiKeyHash("hash-" + UUID.randomUUID())
                .active(true)
                .build());

        notificationScheduler.setBatchSize(100);
        notificationScheduler.setMissedOccurrencePolicy(MissedOccurrencePolicy.SKIP_AND_GENERATE_LATEST);
    }

    private NotificationScheduleEntity createSchedule(
            String name,
            String cron,
            String timezone,
            Instant nextRunAt,
            Instant startAt,
            Instant endAt,
            boolean active
    ) {
        return scheduleRepository.save(NotificationScheduleEntity.builder()
                .applicationId(testApp.getId())
                .name(name)
                .cron(cron)
                .timezone(timezone)
                .channel(NotificationChannel.EMAIL)
                .recipient("test@recurring.com")
                .templateCode("WELCOME")
                .payload(Map.of("user_name", "Ricardo"))
                .active(active)
                .startAt(startAt)
                .endAt(endAt)
                .nextRunAt(nextRunAt != null ? nextRunAt.truncatedTo(ChronoUnit.MICROS) : null)
                .build());
    }

    @Test
    @DisplayName("Gera notificação recorrente com variáveis dinâmicas {{today}} e {{now}} no fuso do agendamento")
    void shouldGenerateNotificationWithDynamicVariablesAndAdvanceSchedule() {
        Instant now = Instant.now();
        Instant dueInstant = now.minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);

        NotificationScheduleEntity schedule = createSchedule(
                "Daily Morning Report",
                "0 0 9 * * *",
                "America/Sao_Paulo",
                dueInstant,
                null,
                null,
                true
        );

        int published = notificationScheduler.processRecurringSchedulesBatch();
        assertThat(published).isEqualTo(1);

        // Verifica a notificação gerada no banco de dados
        List<NotificationEntity> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        NotificationEntity notification = notifications.get(0);

        assertThat(notification.getScheduleId()).isEqualTo(schedule.getId());
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(notification.getIdempotencyKey()).isEqualTo("schedule-" + schedule.getId() + "-" + dueInstant);
        assertThat(notification.getScheduledAt()).isEqualTo(dueInstant);

        // Valida resolução dinâmica de {{today}} e {{now}} no fuso do agendamento
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        ZonedDateTime expectedZdt = dueInstant.atZone(zoneId);
        assertThat(notification.getPayload()).containsEntry("user_name", "Ricardo");
        assertThat(notification.getPayload()).containsEntry("today", expectedZdt.toLocalDate().toString());
        assertThat(notification.getPayload()).containsKey("now");

        // Verifica avanço do agendamento
        NotificationScheduleEntity updatedSchedule = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(updatedSchedule.getLastRunAt()).isEqualTo(dueInstant);
        assertThat(updatedSchedule.getNextRunAt()).isAfter(dueInstant);
        assertThat(updatedSchedule.isActive()).isTrue();

        verify(notificationPublisher, times(1)).publish(notification.getId());
    }

    @Test
    @DisplayName("Pausa e retomada: agendamento pausado não gera notificação; retomar recalcula nextRunAt")
    void shouldHandlePauseAndResumeCorrectly() {
        Instant now = Instant.now();
        NotificationScheduleEntity schedule = createSchedule(
                "Pausable Schedule",
                "0 0 9 * * *",
                "America/Sao_Paulo",
                now.minusSeconds(10),
                null,
                null,
                true
        );

        // Pausa via serviço
        scheduleService.pauseSchedule(schedule.getId(), testApp.getId());

        int processedWhilePaused = notificationScheduler.processRecurringSchedulesBatch();
        assertThat(processedWhilePaused).isEqualTo(0);
        assertThat(notificationRepository.count()).isEqualTo(0);

        // Retoma via serviço
        scheduleService.resumeSchedule(schedule.getId(), testApp.getId());

        NotificationScheduleEntity resumed = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(resumed.isActive()).isTrue();
        assertThat(resumed.getNextRunAt()).isAfter(now);
    }

    @Test
    @DisplayName("Ocorrências perdidas (serviço fora do ar): gera apenas a mais recente com SKIP_AND_GENERATE_LATEST")
    void shouldGenerateOnlyLatestOccurrenceWhenMissedOccurrencesHappen() {
        Instant now = Instant.now();
        // Agendamento a cada minuto ("0 * * * * *") com next_run_at de 4 minutos atrás
        Instant missedPast = now.minusSeconds(240).truncatedTo(ChronoUnit.SECONDS);

        NotificationScheduleEntity schedule = createSchedule(
                "Frequent Minute Cron",
                "0 * * * * *",
                "UTC",
                missedPast,
                null,
                null,
                true
        );

        int processed = notificationScheduler.processRecurringSchedulesBatch();

        // Deve gerar apenas 1 notificação (a mais recente devida)
        assertThat(processed).isEqualTo(1);
        List<NotificationEntity> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);

        NotificationScheduleEntity updated = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(updated.getLastRunAt()).isAfter(missedPast);
        assertThat(updated.getNextRunAt()).isAfter(now);
    }

    @Test
    @DisplayName("endAt vencido: desativa o agendamento (active = false e next_run_at = null)")
    void shouldDeactivateScheduleWhenEndAtExpires() {
        Instant now = Instant.now();
        Instant past = now.minusSeconds(120);
        Instant endAt = now.minusSeconds(10); // endAt já venceu

        NotificationScheduleEntity schedule = createSchedule(
                "Expiring Schedule",
                "0 * * * * *",
                "UTC",
                past,
                null,
                endAt,
                true
        );

        int processed = notificationScheduler.processRecurringSchedulesBatch();

        NotificationScheduleEntity updated = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(updated.isActive()).isFalse();
        assertThat(updated.getNextRunAt()).isNull();
    }

    @Test
    @DisplayName("Concorrência: dois schedulers simultâneos processam agendamentos recorrentes sem duplicar")
    void shouldProcessRecurringSchedulesConcurrentlyWithoutDuplicates() throws InterruptedException, ExecutionException {
        Instant now = Instant.now();
        int totalSchedules = 8;
        for (int i = 0; i < totalSchedules; i++) {
            createSchedule(
                    "Concurrent Schedule " + i,
                    "0 0 9 * * *",
                    "America/Sao_Paulo",
                    now.minusSeconds(i + 5),
                    null,
                    null,
                    true
            );
        }

        notificationScheduler.setBatchSize(4);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<Integer> task = () -> {
            startGate.await();
            int total = 0;
            while (true) {
                int count = notificationScheduler.processRecurringSchedulesBatch();
                total += count;
                if (count == 0) {
                    break;
                }
            }
            return total;
        };

        Future<Integer> worker1 = executor.submit(task);
        Future<Integer> worker2 = executor.submit(task);

        startGate.countDown();

        int p1 = worker1.get();
        int p2 = worker2.get();

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        assertThat(p1 + p2).isEqualTo(totalSchedules);

        List<NotificationEntity> allNotifications = notificationRepository.findAll();
        assertThat(allNotifications).hasSize(totalSchedules);

        // Prova que nenhuma ocorrência foi gerada duas vezes
        Set<String> idempotencyKeys = allNotifications.stream()
                .map(NotificationEntity::getIdempotencyKey)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(idempotencyKeys).hasSize(totalSchedules);

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(notificationPublisher, times(totalSchedules)).publish(captor.capture());
        Set<UUID> publishedIds = Set.copyOf(captor.getAllValues());
        assertThat(publishedIds).hasSize(totalSchedules);
    }
}
