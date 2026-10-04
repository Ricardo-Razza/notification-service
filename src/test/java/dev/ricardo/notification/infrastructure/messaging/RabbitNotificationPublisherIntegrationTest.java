package dev.ricardo.notification.infrastructure.messaging;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationMessage;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RabbitNotificationPublisherIntegrationTest {

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

    @Autowired
    private org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    private String apiKey;
    private ApplicationEntity app;

    @BeforeEach
    void setUp() {
        rabbitListenerEndpointRegistry.stop();
        // Purge a fila principal antes de cada teste
        rabbitTemplate.execute(channel -> {
            channel.queuePurge("notification.queue");
            return null;
        });

        notificationRepository.deleteAll();

        ApiKeyService.GeneratedApiKey key = apiKeyService.generateApiKey();
        apiKey = key.rawApiKey();
        app = applicationRepository.save(ApplicationEntity.builder()
                .name("Rabbit Test App " + UUID.randomUUID())
                .apiKeyHash(key.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Ponta a ponta com RabbitMQ real: mensagem chega na fila notification.queue e status fica QUEUED")
    void shouldPublishMessageToRabbitMqQueueSuccessfully() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "destinatario@fila.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Mensageria")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        // Verificar que o registro no MySQL transicionou para QUEUED
        Optional<NotificationEntity> inDb = notificationRepository.findById(id);
        assertThat(inDb).isPresent();
        assertThat(inDb.get().getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(inDb.get().getApplicationId()).isEqualTo(app.getId());

        // Consumir mensagem da fila notification.queue do RabbitMQ
        Message message = rabbitTemplate.receive("notification.queue", 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);

        JsonNode messageBody = objectMapper.readTree(message.getBody());
        assertThat(messageBody.get("notificationId").asText()).isEqualTo(id.toString());
    }

    @Autowired
    private RabbitNotificationPublisher rabbitNotificationPublisher;

    @Test
    @DisplayName("Falha na publicação: quando RabbitMQ falha, retorna 503 e notificação permanece PENDING no banco")
    void shouldLeaveNotificationPendingWhenPublisherFails() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(rabbitNotificationPublisher, "exchange", "exchange.inexistente");

        try {
            CreateNotificationRequest request = new CreateNotificationRequest(
                    "falha@fila.com",
                    NotificationChannel.EMAIL,
                    "WELCOME",
                    Map.of("name", "Falha Rabbit")
            );

            mockMvc.perform(post("/api/v1/notifications")
                            .header("X-API-Key", apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:service-unavailable"))
                    .andExpect(jsonPath("$.title").value("Service Unavailable"));

            java.util.List<NotificationEntity> list = notificationRepository.findAll();
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(rabbitNotificationPublisher, "exchange", "notification.exchange");
        }
    }
}
