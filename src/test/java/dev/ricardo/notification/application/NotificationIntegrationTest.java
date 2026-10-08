package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class NotificationIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notification_test")
            .withUsername("notification")
            .withPassword("notification");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private dev.ricardo.notification.infrastructure.persistence.ApplicationRepository applicationRepository;

    @Autowired
    private dev.ricardo.notification.domain.ApiKeyService apiKeyService;

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoBean
    private NotificationPublisher notificationPublisher;

    private String testApiKey;
    private dev.ricardo.notification.infrastructure.persistence.ApplicationEntity testApp;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        notificationRepository.deleteAll();

        dev.ricardo.notification.domain.ApiKeyService.GeneratedApiKey key = apiKeyService.generateApiKey();
        testApiKey = key.rawApiKey();
        testApp = applicationRepository.save(dev.ricardo.notification.infrastructure.persistence.ApplicationEntity.builder()
                .name("Integration Test App " + UUID.randomUUID())
                .apiKeyHash(key.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Ponta a ponta: Notificação publicada com sucesso é salva no banco como QUEUED e grava application_id")
    void shouldExecuteFullFlowAndSaveSentStatusInDatabase() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@dominio.com.br",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo Razza")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", testApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.sentAt").doesNotExist())
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        verify(notificationPublisher).publish(id);

        Optional<NotificationEntity> inDb = notificationRepository.findById(id);
        assertThat(inDb).isPresent();
        assertThat(inDb.get().getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(inDb.get().getSentAt()).isNull();
        assertThat(inDb.get().getAttempts()).isEqualTo(0);
        assertThat(inDb.get().getLastError()).isNull();
        assertThat(inDb.get().getApplicationId()).isEqualTo(testApp.getId());
    }

    @Test
    @DisplayName("Ponta a ponta: Falha na publicação da fila mantém status PENDING e responde 503")
    void shouldExecuteFullFlowAndSaveFailedStatusWhenMailSenderFails() throws Exception {
        doThrow(new dev.ricardo.notification.domain.NotificationPublishException("RabbitMQ broker connection refused"))
                .when(notificationPublisher).publish(any(UUID.class));

        CreateNotificationRequest request = new CreateNotificationRequest(
                "falha@dominio.com.br",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Usuário Falha")
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", testApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:problem-type:service-unavailable"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"));

        // Verificar que o registro criado no banco permaneceu PENDING
        List<NotificationEntity> entities = notificationRepository.findAll();
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(entities.get(0).getAttempts()).isEqualTo(0);
        assertThat(entities.get(0).getApplicationId()).isEqualTo(testApp.getId());
    }

    @Test
    @DisplayName("Ponta a ponta: Requisição para /api/v1/notifications sem X-API-Key deve responder 401 ProblemDetail")
    void shouldReturn401WhenApiKeyIsMissing() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@dominio.com.br",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo")
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }
}
