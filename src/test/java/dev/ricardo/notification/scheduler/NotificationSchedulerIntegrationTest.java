package dev.ricardo.notification.scheduler;

import dev.ricardo.notification.application.NotificationPublisher;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class NotificationSchedulerIntegrationTest {

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

        // Desabilita execução periódica automática do scheduler para controle fino nos testes
        registry.add("app.scheduler.enabled", () -> "false");
    }

    @Autowired
    private NotificationScheduler notificationScheduler;

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
        clearInvocations(notificationPublisher);

        testApp = applicationRepository.save(ApplicationEntity.builder()
                .name("Scheduler Test App " + UUID.randomUUID())
                .apiKeyHash("hash-" + UUID.randomUUID())
                .active(true)
                .build());

        notificationScheduler.setBatchSize(100);
        notificationScheduler.setMaxLatenessSeconds(3600);
    }

    private NotificationEntity createNotification(NotificationStatus status, Instant scheduledAt) {
        return notificationRepository.save(NotificationEntity.builder()
                .applicationId(testApp.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("test@sched.com")
                .templateCode("WELCOME")
                .status(status)
                .attempts(0)
                .scheduledAt(scheduledAt != null ? scheduledAt.truncatedTo(ChronoUnit.MICROS) : null)
                .build());
    }

    @Test
    @DisplayName("Libera apenas notificações SCHEDULED que venceram (scheduled_at <= agora)")
    void shouldReleaseOnlyDueScheduledNotifications() {
        Instant now = Instant.now();
        NotificationEntity due = createNotification(NotificationStatus.SCHEDULED, now.minusSeconds(10));
        NotificationEntity future = createNotification(NotificationStatus.SCHEDULED, now.plusSeconds(300));
        NotificationEntity alreadyQueued = createNotification(NotificationStatus.QUEUED, now.minusSeconds(10));

        int released = notificationScheduler.processScheduledBatch();

        assertThat(released).isEqualTo(1);

        NotificationEntity updatedDue = notificationRepository.findById(due.getId()).orElseThrow();
        assertThat(updatedDue.getStatus()).isEqualTo(NotificationStatus.QUEUED);

        NotificationEntity updatedFuture = notificationRepository.findById(future.getId()).orElseThrow();
        assertThat(updatedFuture.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);

        NotificationEntity updatedQueued = notificationRepository.findById(alreadyQueued.getId()).orElseThrow();
        assertThat(updatedQueued.getStatus()).isEqualTo(NotificationStatus.QUEUED);

        verify(notificationPublisher, times(1)).publish(due.getId());
        verify(notificationPublisher, never()).publish(future.getId());
        verify(notificationPublisher, never()).publish(alreadyQueued.getId());
    }

    @Test
    @DisplayName("Respeita o tamanho configurável do lote (batch-size)")
    void shouldRespectConfiguredBatchSize() {
        notificationScheduler.setBatchSize(2);

        Instant now = Instant.now();
        List<NotificationEntity> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createNotification(NotificationStatus.SCHEDULED, now.minusSeconds(10 + i)));
        }

        int releasedBatch1 = notificationScheduler.processScheduledBatch();
        assertThat(releasedBatch1).isEqualTo(2);

        long queuedCount = notificationRepository.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.QUEUED)
                .count();
        long scheduledCount = notificationRepository.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.SCHEDULED)
                .count();

        assertThat(queuedCount).isEqualTo(2);
        assertThat(scheduledCount).isEqualTo(3);

        int releasedBatch2 = notificationScheduler.processScheduledBatch();
        assertThat(releasedBatch2).isEqualTo(2);

        int releasedBatch3 = notificationScheduler.processScheduledBatch();
        assertThat(releasedBatch3).isEqualTo(1);

        int releasedBatch4 = notificationScheduler.processScheduledBatch();
        assertThat(releasedBatch4).isEqualTo(0);
    }

    @Test
    @DisplayName("Política de atraso: marca como FAILED com last_error se venceu há mais que max-lateness")
    void shouldMarkAsFailedWhenExceedingMaxLateness() {
        notificationScheduler.setMaxLatenessSeconds(60); // 1 minuto de tolerância

        Instant now = Instant.now();
        NotificationEntity normalDue = createNotification(NotificationStatus.SCHEDULED, now.minusSeconds(30));
        NotificationEntity expiredDue = createNotification(NotificationStatus.SCHEDULED, now.minusSeconds(120));

        int released = notificationScheduler.processScheduledBatch();

        // Apenas o normalDue é liberado para a fila
        assertThat(released).isEqualTo(1);

        NotificationEntity updatedNormal = notificationRepository.findById(normalDue.getId()).orElseThrow();
        assertThat(updatedNormal.getStatus()).isEqualTo(NotificationStatus.QUEUED);

        NotificationEntity updatedExpired = notificationRepository.findById(expiredDue.getId()).orElseThrow();
        assertThat(updatedExpired.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(updatedExpired.getLastError()).contains("Notification expired");
        assertThat(updatedExpired.getLastError()).contains("exceeded max-lateness threshold");

        verify(notificationPublisher, times(1)).publish(normalDue.getId());
        verify(notificationPublisher, never()).publish(expiredDue.getId());
    }

    @Test
    @DisplayName("Concorrência: dois schedulers simultâneos processam com SKIP LOCKED sem duplicar publicações")
    void shouldProcessWithoutDuplicatesUnderConcurrentSchedulers() throws InterruptedException, ExecutionException {
        Instant now = Instant.now();
        int totalNotifications = 12;
        List<NotificationEntity> notifications = new ArrayList<>();
        for (int i = 0; i < totalNotifications; i++) {
            notifications.add(createNotification(NotificationStatus.SCHEDULED, now.minusSeconds(i + 1)));
        }

        notificationScheduler.setBatchSize(5);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<Integer> schedulerTask = () -> {
            startGate.await();
            int totalProcessedByThisInstance = 0;
            while (true) {
                int count = notificationScheduler.processScheduledBatch();
                totalProcessedByThisInstance += count;
                if (count == 0) {
                    break;
                }
            }
            return totalProcessedByThisInstance;
        };

        Future<Integer> worker1 = executor.submit(schedulerTask);
        Future<Integer> worker2 = executor.submit(schedulerTask);

        // Dispara ambos ao mesmo tempo
        startGate.countDown();

        int processed1 = worker1.get();
        int processed2 = worker2.get();

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // A soma dos processamentos deve ser exatamente o número total de notificações
        assertThat(processed1 + processed2).isEqualTo(totalNotifications);

        // Todas no banco viraram QUEUED
        List<NotificationEntity> allInDb = notificationRepository.findAll();
        assertThat(allInDb).hasSize(totalNotifications);
        assertThat(allInDb).allMatch(n -> n.getStatus() == NotificationStatus.QUEUED);

        // Cada notificação foi publicada exatamente 1 vez
        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(notificationPublisher, times(totalNotifications)).publish(captor.capture());

        List<UUID> publishedIds = captor.getAllValues();
        Set<UUID> uniquePublishedIds = Set.copyOf(publishedIds);
        assertThat(uniquePublishedIds).hasSize(totalNotifications);
    }
}
