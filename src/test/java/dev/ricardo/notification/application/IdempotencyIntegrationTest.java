package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class IdempotencyIntegrationTest {

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
    private ApplicationRepository applicationRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoBean
    private NotificationPublisher notificationPublisher;

    private String apiKey1;
    private ApplicationEntity app1;
    private String apiKey2;
    private ApplicationEntity app2;

    @BeforeEach
    void setUp() {
        Mockito.clearInvocations(mailSender);
        Mockito.clearInvocations(notificationPublisher);

        ApiKeyService.GeneratedApiKey key1 = apiKeyService.generateApiKey();
        apiKey1 = key1.rawApiKey();
        app1 = applicationRepository.save(ApplicationEntity.builder()
                .name("App 1 - " + UUID.randomUUID())
                .apiKeyHash(key1.hash())
                .active(true)
                .build());

        ApiKeyService.GeneratedApiKey key2 = apiKeyService.generateApiKey();
        apiKey2 = key2.rawApiKey();
        app2 = applicationRepository.save(ApplicationEntity.builder()
                .name("App 2 - " + UUID.randomUUID())
                .apiKeyHash(key2.hash())
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Repetição simples: mesma chave na mesma aplicação retorna 200 OK com Idempotent-Replayed: true e só publica 1 vez")
    void shouldReplayExistingNotificationWhenSameKeyIsUsed() throws Exception {
        String idempotencyKey = "req-" + UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "idempotencia@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Idempotente")
        );

        // 1ª Requisição: criação (202 Accepted)
        MvcResult firstResult = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        String firstId = firstJson.get("id").asText();

        // 2ª Requisição: repetição (200 OK com Idempotent-Replayed: true)
        MvcResult secondResult = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        JsonNode secondJson = objectMapper.readTree(secondResult.getResponse().getContentAsString());
        String secondId = secondJson.get("id").asText();

        assertThat(firstId).isEqualTo(secondId);
        // O publisher deve ter sido invocado apenas UMA vez
        verify(notificationPublisher, times(1)).publish(any(UUID.class));
    }

    @Test
    @DisplayName("Isolamento: mesma idempotency_key em duas aplicações diferentes cria duas notificações distintas")
    void shouldIsolateIdempotencyKeyBetweenApplications() throws Exception {
        String sharedKey = "shared-order-key-" + UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "isolamento@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Multi Tenant")
        );

        // Disparo pela App 1
        MvcResult resultApp1 = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey1)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        // Disparo pela App 2 com a MESMA chave
        MvcResult resultApp2 = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", apiKey2)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode json1 = objectMapper.readTree(resultApp1.getResponse().getContentAsString());
        JsonNode json2 = objectMapper.readTree(resultApp2.getResponse().getContentAsString());

        String id1 = json1.get("id").asText();
        String id2 = json2.get("id").asText();

        assertThat(id1).isNotEqualTo(id2);
        verify(notificationPublisher, times(2)).publish(any(UUID.class));
    }

    @Test
    @DisplayName("Concorrência: múltiplas threads simultâneas com a mesma chave geram exatamente 1 notificação e 1 publicação")
    void shouldHandleConcurrentRequestsSafely() throws Exception {
        String concurrentKey = "race-key-" + UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "concorrencia@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Concorrente")
        );
        String requestJson = objectMapper.writeValueAsString(request);

        int totalThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger replayedCount = new AtomicInteger(0);
        List<Future<MvcResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(); // Aguarda todas as threads estarem prontas para disparo simultâneo
                return mockMvc.perform(post("/api/v1/notifications")
                                .header("X-API-Key", apiKey1)
                                .header("Idempotency-Key", concurrentKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson))
                        .andReturn();
            }));
        }

        readyLatch.await();
        startLatch.countDown(); // Dispara todas simultaneamente

        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            int status = result.getResponse().getStatus();
            if (status == 202) {
                createdCount.incrementAndGet();
            } else if (status == 200 && "true".equals(result.getResponse().getHeader("Idempotent-Replayed"))) {
                replayedCount.incrementAndGet();
            }
        }

        executor.shutdown();

        // Exatamente UMA thread obteve 202 Accepted
        assertThat(createdCount.get()).isEqualTo(1);
        // Todas as outras obtiveram 200 com Idempotent-Replayed: true
        assertThat(replayedCount.get()).isEqualTo(totalThreads - 1);

        // A publicação na fila ocorreu exatamente UMA vez
        verify(notificationPublisher, times(1)).publish(any(UUID.class));

        // No banco de dados, existe exatamente uma notificação para essa chave
        assertThat(notificationRepository.findByApplicationIdAndIdempotencyKey(app1.getId(), concurrentKey))
                .isPresent();
    }
}
