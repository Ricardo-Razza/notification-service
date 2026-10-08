package dev.ricardo.notification.application;

import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.AttemptStatus;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.email.EmailNotificationSender;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class NotificationRetryIntegrationTest {

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

        registry.add("app.admin.token", () -> "test-admin-secret-token");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationAttemptRepository attemptRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @MockitoSpyBean
    private EmailNotificationSender emailSender;

    @Value("${app.admin.token}")
    private String adminToken;

    private String app1ApiKey;
    private ApplicationEntity app1;

    private String app2ApiKey;
    private ApplicationEntity app2;

    @BeforeEach
    void setUp() {
        reset(emailSender);
        Mockito.doNothing().when(emailSender).send(any());

        rabbitTemplate.execute(channel -> {
            channel.queuePurge("notification.queue");
            channel.queuePurge("notification.retry-1");
            channel.queuePurge("notification.retry-2");
            channel.queuePurge("notification.retry-3");
            channel.queuePurge("notification.dlq");
            return null;
        });

        attemptRepository.deleteAll();
        notificationRepository.deleteAll();

        ApiKeyService.GeneratedApiKey key1 = apiKeyService.generateApiKey();
        app1ApiKey = key1.rawApiKey();
        app1 = applicationRepository.save(ApplicationEntity.builder()
                .name("App One " + UUID.randomUUID())
                .apiKeyHash(key1.hash())
                .active(true)
                .build());

        ApiKeyService.GeneratedApiKey key2 = apiKeyService.generateApiKey();
        app2ApiKey = key2.rawApiKey();
        app2 = applicationRepository.save(ApplicationEntity.builder()
                .name("App Two " + UUID.randomUUID())
                .apiKeyHash(key2.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Reprocessamento com sucesso: notificação FAILED volta para QUEUED, worker processa, vira SENT com tentativa 4")
    void shouldRetryFailedNotificationSuccessfully() throws Exception {
        NotificationEntity failedEntity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app1.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("retry.success@test.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "Reprocessamento"))
                .status(NotificationStatus.FAILED)
                .attempts(3)
                .lastError("SMTP timeout on attempt 3")
                .build());

        for (int i = 1; i <= 3; i++) {
            attemptRepository.save(NotificationAttemptEntity.builder()
                    .id(UUID.randomUUID())
                    .notificationId(failedEntity.getId())
                    .attemptNumber(i)
                    .status(AttemptStatus.FAILURE)
                    .errorMessage("Failure on attempt " + i)
                    .startedAt(Instant.now().minusSeconds(60 * (4 - i)))
                    .finishedAt(Instant.now().minusSeconds(60 * (4 - i) - 1))
                    .build());
        }

        mockMvc.perform(post("/api/v1/notifications/{id}/retry", failedEntity.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.attemptsHistory").isArray())
                .andExpect(jsonPath("$.attemptsHistory.length()").value(3));

        waitFor(() -> {
            Optional<NotificationEntity> opt = notificationRepository.findById(failedEntity.getId());
            return opt.isPresent() && opt.get().getStatus() == NotificationStatus.SENT;
        }, Duration.ofSeconds(5));

        NotificationEntity updated = notificationRepository.findById(failedEntity.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(updated.getAttempts()).isEqualTo(4);

        List<NotificationAttemptEntity> allAttempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(failedEntity.getId());
        assertThat(allAttempts).hasSize(4);
        assertThat(allAttempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(allAttempts.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(allAttempts.get(2).getAttemptNumber()).isEqualTo(3);
        assertThat(allAttempts.get(3).getAttemptNumber()).isEqualTo(4);
        assertThat(allAttempts.get(3).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
    }

    @Test
    @DisplayName("Status inválido: 409 Conflict ao tentar reprocessar notificação que não está FAILED")
    void shouldReturn409WhenNotificationIsNotFailed() throws Exception {
        NotificationEntity sentEntity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app1.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("sent@test.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "Ja Enviado"))
                .status(NotificationStatus.SENT)
                .attempts(1)
                .sentAt(Instant.now())
                .build());

        mockMvc.perform(post("/api/v1/notifications/{id}/retry", sentEntity.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:conflict"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("only FAILED notifications can be retried")));
    }

    @Test
    @DisplayName("Outra aplicação: 404 Not Found ao tentar reprocessar notificação de outro tenant")
    void shouldReturn404WhenRetryingNotificationOfAnotherApplication() throws Exception {
        NotificationEntity app2Entity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app2.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("app2@test.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "App 2"))
                .status(NotificationStatus.FAILED)
                .attempts(3)
                .lastError("Error")
                .build());

        mockMvc.perform(post("/api/v1/notifications/{id}/retry", app2Entity.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    @Test
    @DisplayName("Health check reflete banco MySQL e RabbitMQ publicamente sem token")
    void shouldExposePublicHealthReflectingDbAndRabbitMQ() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("Métricas do actuator protegidas por X-Admin-Token")
    void shouldProtectActuatorMetricsRequiringAdminToken() throws Exception {
        // Sem token -> 401
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"));

        // Com token inválido -> 401
        mockMvc.perform(get("/actuator/metrics")
                        .header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        // Com token válido -> 200
        mockMvc.perform(get("/actuator/metrics")
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());

        // Métrica específica com token -> 200
        meterRegistry.counter("notifications.attempts", "channel", "EMAIL").increment();
        mockMvc.perform(get("/actuator/metrics/notifications.attempts")
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("notifications.attempts"));
    }

    private void waitFor(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}
