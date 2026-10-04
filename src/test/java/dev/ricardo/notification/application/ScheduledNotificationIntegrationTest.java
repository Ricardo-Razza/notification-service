package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ScheduledNotificationIntegrationTest {

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

        registry.add("app.scheduling.max-future-days", () -> "365");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    private String app1ApiKey;
    private ApplicationEntity app1;

    private String app2ApiKey;
    private ApplicationEntity app2;

    @BeforeEach
    void setUp() {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge("notification.queue");
            channel.queuePurge("notification.dlq");
            return null;
        });

        notificationRepository.deleteAll();

        ApiKeyService.GeneratedApiKey key1 = apiKeyService.generateApiKey();
        app1ApiKey = key1.rawApiKey();
        app1 = applicationRepository.save(ApplicationEntity.builder()
                .name("App One Scheduled " + UUID.randomUUID())
                .apiKeyHash(key1.hash())
                .active(true)
                .build());

        ApiKeyService.GeneratedApiKey key2 = apiKeyService.generateApiKey();
        app2ApiKey = key2.rawApiKey();
        app2 = applicationRepository.save(ApplicationEntity.builder()
                .name("App Two Scheduled " + UUID.randomUUID())
                .apiKeyHash(key2.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Criação agendada válida: status SCHEDULED, grava UTC, NÃO publica na fila e responde 202 com scheduledAt")
    void shouldCreateScheduledNotificationSuccessfullyAndNotPublishToQueue() throws Exception {
        OffsetDateTime futureTime = OffsetDateTime.now(ZoneOffset.ofHours(-3)).plusHours(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        CreateNotificationRequest request = new CreateNotificationRequest(
                "agendado@teste.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Futuro"),
                futureTime.toString()
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", app1ApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.scheduledAt").isNotEmpty())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        // Verifica no banco de dados
        Optional<NotificationEntity> inDb = notificationRepository.findById(id);
        assertThat(inDb).isPresent();
        assertThat(inDb.get().getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(inDb.get().getScheduledAt()).isEqualTo(futureTime.toInstant());

        // Garante que NADA foi para a fila do RabbitMQ
        Message message = rabbitTemplate.receive("notification.queue", 1000);
        assertThat(message).isNull();
    }

    @Test
    @DisplayName("Data no passado: 400 Bad Request ao agendar notificação com data anterior ao instante atual")
    void shouldRejectScheduledAtInThePast() throws Exception {
        OffsetDateTime pastTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);

        CreateNotificationRequest request = new CreateNotificationRequest(
                "passado@teste.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Passado"),
                pastTime.toString()
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", app1ApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                .andExpect(jsonPath("$.detail").value("scheduledAt must be in the future"));
    }

    @Test
    @DisplayName("Data além do limite: 400 Bad Request quando scheduledAt ultrapassa max-future-days (1 ano)")
    void shouldRejectScheduledAtExceedingMaxFutureLimit() throws Exception {
        OffsetDateTime distantFuture = OffsetDateTime.now(ZoneOffset.UTC).plusDays(400);

        CreateNotificationRequest request = new CreateNotificationRequest(
                "limite@teste.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Muito Longe"),
                distantFuture.toString()
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", app1ApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maximum future scheduling limit")));
    }

    @Test
    @DisplayName("Formato inválido: 400 Bad Request quando scheduledAt não é ISO-8601 válido")
    void shouldRejectInvalidScheduledAtFormat() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "formato@teste.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Formato Ruim"),
                "2026-15-99 25:61"
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", app1ApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Invalid scheduledAt format")));
    }

    @Test
    @DisplayName("Cancelamento válido: DELETE /notifications/{id} cancela notificação SCHEDULED (vira CANCELED) e mostra scheduledAt")
    void shouldCancelScheduledNotificationSuccessfully() throws Exception {
        Instant scheduledTime = Instant.now().plus(Duration.ofHours(3));
        NotificationEntity entity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app1.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("cancelar@teste.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "Cancelar"))
                .status(NotificationStatus.SCHEDULED)
                .scheduledAt(scheduledTime)
                .attempts(0)
                .build());

        mockMvc.perform(delete("/api/v1/notifications/{id}", entity.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entity.getId().toString()))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.scheduledAt").isNotEmpty());

        NotificationEntity reloaded = notificationRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.CANCELED);
    }

    @Test
    @DisplayName("Cancelamento inválido: 409 Conflict ao tentar cancelar notificação que não está SCHEDULED")
    void shouldRejectCancelWhenNotificationNotScheduled() throws Exception {
        NotificationEntity sentEntity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app1.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("sent.cancel@teste.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "Ja Enviado"))
                .status(NotificationStatus.SENT)
                .attempts(1)
                .sentAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/v1/notifications/{id}", sentEntity.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:conflict"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("only SCHEDULED notifications can be canceled")));
    }

    @Test
    @DisplayName("Isolamento multitenant no cancelamento: 404 Not Found ao tentar cancelar notificação de outro tenant")
    void shouldReturn404WhenCancelingNotificationOfAnotherApplication() throws Exception {
        NotificationEntity app2Scheduled = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app2.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("app2.scheduled@teste.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "App 2"))
                .status(NotificationStatus.SCHEDULED)
                .scheduledAt(Instant.now().plus(Duration.ofHours(1)))
                .attempts(0)
                .build());

        mockMvc.perform(delete("/api/v1/notifications/{id}", app2Scheduled.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:not-found"));
    }

    @Test
    @DisplayName("GET /notifications/{id} exibe o campo scheduledAt")
    void shouldShowScheduledAtInGetNotificationById() throws Exception {
        Instant scheduledTime = Instant.now().plus(Duration.ofHours(5));
        NotificationEntity entity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app1.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("consulta.agendado@teste.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "Consulta"))
                .status(NotificationStatus.SCHEDULED)
                .scheduledAt(scheduledTime)
                .attempts(0)
                .build());

        mockMvc.perform(get("/api/v1/notifications/{id}", entity.getId())
                        .header("X-API-Key", app1ApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entity.getId().toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.scheduledAt").isNotEmpty());
    }
}
