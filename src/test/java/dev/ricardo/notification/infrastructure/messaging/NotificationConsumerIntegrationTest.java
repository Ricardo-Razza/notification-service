package dev.ricardo.notification.infrastructure.messaging;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.AttemptStatus;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationMessage;
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
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationConsumerIntegrationTest {

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

        // TTLs curtos para testes rápidos de retry
        registry.add("app.rabbitmq.retry-1-ttl", () -> "300");
        registry.add("app.rabbitmq.retry-2-ttl", () -> "500");
        registry.add("app.rabbitmq.retry-3-ttl", () -> "800");
        registry.add("app.notification.max-attempts", () -> "3");
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
    private NotificationAttemptRepository attemptRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @MockitoSpyBean
    private EmailNotificationSender emailSender;

    private String apiKey;
    private ApplicationEntity app;

    @BeforeEach
    void setUp() {
        reset(emailSender);
        Mockito.doNothing().when(emailSender).send(any());

        rabbitTemplate.execute(channel -> {
            channel.queuePurge("notification.queue");
            channel.queuePurge("notification.retry.10s");
            channel.queuePurge("notification.retry.30s");
            channel.queuePurge("notification.retry.2m");
            channel.queuePurge("notification.dlq");
            return null;
        });

        attemptRepository.deleteAll();
        notificationRepository.deleteAll();

        ApiKeyService.GeneratedApiKey key = apiKeyService.generateApiKey();
        apiKey = key.rawApiKey();
        app = applicationRepository.save(ApplicationEntity.builder()
                .name("Consumer Test App " + UUID.randomUUID())
                .apiKeyHash(key.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Sucesso na primeira tentativa: notificação vai para SENT, attempts = 1 e 1 tentativa SUCCESS")
    void shouldProcessNotificationSuccessfullyOnFirstAttempt() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "sucesso@worker.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Primeira Tentativa")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        // Espera o worker processar e atualizar o status para SENT
        waitFor(() -> {
            Optional<NotificationEntity> opt = notificationRepository.findById(id);
            return opt.isPresent() && opt.get().getStatus() == NotificationStatus.SENT;
        }, Duration.ofSeconds(5));

        NotificationEntity entity = notificationRepository.findById(id).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(entity.getSentAt()).isNotNull();
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getLastError()).isNull();

        List<NotificationAttemptEntity> attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(attempts.get(0).getErrorMessage()).isNull();
        assertThat(attempts.get(0).getStartedAt()).isNotNull();
        assertThat(attempts.get(0).getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Falha seguida de sucesso: faz retry pela fila de retry e conclui com status SENT e 2 tentativas")
    void shouldRetryOnFailureAndSucceedOnSecondAttempt() throws Exception {
        // Falha na primeira tentativa, sucede na segunda
        doThrow(new RuntimeException("Simulated SMTP error"))
                .doNothing()
                .when(emailSender).send(any());

        CreateNotificationRequest request = new CreateNotificationRequest(
                "retry@worker.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Com Retry")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        // Espera o retry completar via RabbitMQ TTL e terminar em SENT
        waitFor(() -> {
            Optional<NotificationEntity> opt = notificationRepository.findById(id);
            return opt.isPresent() && opt.get().getStatus() == NotificationStatus.SENT;
        }, Duration.ofSeconds(10));

        NotificationEntity entity = notificationRepository.findById(id).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(entity.getAttempts()).isEqualTo(2);

        List<NotificationAttemptEntity> attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.FAILURE);
        assertThat(attempts.get(0).getErrorMessage()).isEqualTo("Simulated SMTP error");
        assertThat(attempts.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(attempts.get(1).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
    }

    @Test
    @DisplayName("Falha até esgotar: termina em FAILED, registra 3 tentativas e publica na DLQ")
    void shouldExhaustRetriesAndMoveToDlq() throws Exception {
        doThrow(new RuntimeException("Fatal SMTP error"))
                .when(emailSender).send(any());

        CreateNotificationRequest request = new CreateNotificationRequest(
                "dlq@worker.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Vai Para DLQ")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        // Espera esgotar as 3 tentativas e virar FAILED
        waitFor(() -> {
            Optional<NotificationEntity> opt = notificationRepository.findById(id);
            return opt.isPresent() && opt.get().getStatus() == NotificationStatus.FAILED;
        }, Duration.ofSeconds(15));

        NotificationEntity entity = notificationRepository.findById(id).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(entity.getAttempts()).isEqualTo(3);
        assertThat(entity.getLastError()).isEqualTo("Fatal SMTP error");

        List<NotificationAttemptEntity> attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
        assertThat(attempts).hasSize(3);
        assertThat(attempts).allMatch(a -> a.getStatus() == AttemptStatus.FAILURE);

        // Verifica que a mensagem chegou na DLQ
        Message dlqMessage = rabbitTemplate.receive("notification.dlq", 5000);
        assertThat(dlqMessage).isNotNull();
        JsonNode dlqJson = objectMapper.readTree(dlqMessage.getBody());
        assertThat(dlqJson.get("notificationId").asText()).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("Idempotência do worker: mensagem duplicada para notificação já SENT é descartada com ACK")
    void shouldDiscardDuplicateMessageWhenAlreadySent() throws Exception {
        NotificationEntity entity = notificationRepository.save(NotificationEntity.builder()
                .applicationId(app.getId())
                .channel(NotificationChannel.EMAIL)
                .recipient("duplicada@worker.com")
                .templateCode("WELCOME")
                .payload(Map.of("name", "Já Enviada"))
                .status(NotificationStatus.SENT)
                .attempts(1)
                .sentAt(Instant.now())
                .build());

        // Publica mensagem da notificação já enviada diretamente na fila
        rabbitTemplate.convertAndSend("notification.exchange", "notification.send", new NotificationMessage(entity.getId()));

        // Aguarda um instante para o worker processar
        Thread.sleep(1000);

        NotificationEntity reloaded = notificationRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(entity.getId())).isEmpty();
    }

    @Test
    @DisplayName("Mensagem malformada: vai diretamente para a DLQ sem ficar em loop")
    void shouldSendMalformedMessageDirectlyToDlqWithoutLooping() throws Exception {
        byte[] malformedBytes = "{\"corrupted_json\": true".getBytes(StandardCharsets.UTF_8);
        Message malformedMessage = MessageBuilder.withBody(malformedBytes)
                .setContentType("application/json")
                .build();

        rabbitTemplate.send("notification.exchange", "notification.send", malformedMessage);

        // A mensagem deve ir para a DLQ e sair da fila principal
        Message dlqMessage = null;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            dlqMessage = rabbitTemplate.receive("notification.dlq", 500);
            if (dlqMessage != null) break;
            Thread.sleep(100);
        }

        assertThat(dlqMessage).isNotNull();
        assertThat(new String(dlqMessage.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"corrupted_json\": true");

        // Fila principal deve estar vazia (sem loop de reentrega)
        Message mainMessage = rabbitTemplate.receive("notification.queue", 500);
        assertThat(mainMessage).isNull();
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} inclui a lista de tentativas com detalhes")
    void shouldIncludeAttemptsHistoryInGetNotificationById() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "historico@worker.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Historico Tentativas")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        waitFor(() -> {
            Optional<NotificationEntity> opt = notificationRepository.findById(id);
            return opt.isPresent() && opt.get().getStatus() == NotificationStatus.SENT;
        }, Duration.ofSeconds(5));

        mockMvc.perform(get("/api/v1/notifications/{id}", id)
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.attemptsHistory").isArray())
                .andExpect(jsonPath("$.attemptsHistory[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.attemptsHistory[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.attemptsHistory[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$.attemptsHistory[0].finishedAt").isNotEmpty());
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
