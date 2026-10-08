package dev.ricardo.notification.application;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class NotificationHistoryIntegrationTest {

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
    private NotificationRepository notificationRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @MockitoBean
    private JavaMailSender mailSender;

    private ApplicationEntity appA;
    private String apiKeyA;

    private ApplicationEntity appB;
    private String apiKeyB;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();

        ApiKeyService.GeneratedApiKey keyA = apiKeyService.generateApiKey();
        apiKeyA = keyA.rawApiKey();
        appA = applicationRepository.save(ApplicationEntity.builder()
                .name("App A " + UUID.randomUUID())
                .apiKeyHash(keyA.hash())
                .active(true)
                .build());

        ApiKeyService.GeneratedApiKey keyB = apiKeyService.generateApiKey();
        apiKeyB = keyB.rawApiKey();
        appB = applicationRepository.save(ApplicationEntity.builder()
                .name("App B " + UUID.randomUUID())
                .apiKeyHash(keyB.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Filtros combinados: deve filtrar por status, canal e recipient")
    void shouldFilterByStatusChannelAndRecipient() throws Exception {
        createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "alvo@empresa.com");
        createNotification(appA.getId(), NotificationStatus.FAILED, NotificationChannel.EMAIL, "alvo@empresa.com");
        createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.SMS, "alvo@empresa.com");
        createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "outro@empresa.com");

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyA)
                        .param("status", "SENT")
                        .param("channel", "EMAIL")
                        .param("recipient", "alvo@empresa.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status", is("SENT")))
                .andExpect(jsonPath("$.items[0].channel", is("EMAIL")))
                .andExpect(jsonPath("$.items[0].recipient", is("alvo@empresa.com")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("Paginação e ordenação fixa por created_at decrescente")
    void shouldPaginateAndOrderCorrectly() throws Exception {
        for (int i = 0; i < 25; i++) {
            createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "user" + i + "@empresa.com");
        }

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyA)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(10)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", is(25)))
                .andExpect(jsonPath("$.totalPages", is(3)));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyA)
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.page", is(2)))
                .andExpect(jsonPath("$.totalPages", is(3)));
    }

    @Test
    @DisplayName("Limite de size: size maior que 100 deve retornar 400 Bad Request")
    void shouldRejectSizeGreaterThan100() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyA)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", is("urn:problem-type:bad-request")))
                .andExpect(jsonPath("$.detail", is("Page size must be between 1 and 100")));
    }

    @Test
    @DisplayName("Isolamento no histórico: App A enxerga APENAS as suas notificações")
    void shouldIsolateHistoryBetweenApplications() throws Exception {
        createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "a@empresa.com");
        createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "a2@empresa.com");
        createNotification(appB.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "b@empresa.com");

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.items[0].recipient", is("a2@empresa.com")))
                .andExpect(jsonPath("$.items[1].recipient", is("a@empresa.com")));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].recipient", is("b@empresa.com")));
    }

    @Test
    @DisplayName("Isolamento por ID: App A acessando notificação de App B via GET /{id} recebe 404")
    void shouldReturn404WhenAccessingNotificationOfAnotherApplication() throws Exception {
        NotificationEntity notificationB = createNotification(
                appB.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "b@empresa.com"
        );

        // App B consegue acessar normalmente
        mockMvc.perform(get("/api/v1/notifications/{id}", notificationB.getId())
                        .header("X-API-Key", apiKeyB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(notificationB.getId().toString())));

        // App A tenta acessar notificação do App B -> 404 Not Found
        mockMvc.perform(get("/api/v1/notifications/{id}", notificationB.getId())
                        .header("X-API-Key", apiKeyA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", is("urn:problem-type:not-found")));
    }

    @Test
    @DisplayName("Filtro por intervalo de datas createdFrom e createdTo")
    void shouldFilterByDateRange() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        createNotification(appA.getId(), NotificationStatus.SENT, NotificationChannel.EMAIL, "antigo@empresa.com");

        String from = now.minus(5, ChronoUnit.MINUTES).toString();
        String to = now.plus(5, ChronoUnit.MINUTES).toString();

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-API-Key", apiKeyA)
                        .param("createdFrom", from)
                        .param("createdTo", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].recipient", is("antigo@empresa.com")));
    }

    private NotificationEntity createNotification(UUID appId, NotificationStatus status, NotificationChannel channel, String recipient) {
        return notificationRepository.save(NotificationEntity.builder()
                .applicationId(appId)
                .channel(channel)
                .recipient(recipient)
                .templateCode("WELCOME")
                .payload(Map.of("name", "Teste"))
                .status(status)
                .attempts(1)
                .sentAt(status == NotificationStatus.SENT ? Instant.now() : null)
                .build());
    }
}
