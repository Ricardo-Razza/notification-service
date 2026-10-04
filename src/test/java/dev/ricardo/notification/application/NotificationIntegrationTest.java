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

    @MockitoBean
    private JavaMailSender mailSender;

    @Test
    @DisplayName("Ponta a ponta: Notificação enviada com sucesso é salva no banco como SENT")
    void shouldExecuteFullFlowAndSaveSentStatusInDatabase() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@dominio.com.br",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo Razza")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.sentAt").exists())
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mailCaptor.capture());
        SimpleMailMessage sentMail = mailCaptor.getValue();
        assertThat(sentMail.getTo()).contains("ricardo@dominio.com.br");
        assertThat(sentMail.getSubject()).isEqualTo("Bem-vindo, Ricardo Razza!");
        assertThat(sentMail.getText()).contains("seja bem-vindo ao sistema.");

        Optional<NotificationEntity> inDb = notificationRepository.findById(id);
        assertThat(inDb).isPresent();
        assertThat(inDb.get().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(inDb.get().getSentAt()).isNotNull();
        assertThat(inDb.get().getAttempts()).isEqualTo(1);
        assertThat(inDb.get().getLastError()).isNull();
    }

    @Test
    @DisplayName("Ponta a ponta: Falha do JavaMailSender grava status FAILED no banco sem retornar 500")
    void shouldExecuteFullFlowAndSaveFailedStatusWhenMailSenderFails() throws Exception {
        doThrow(new MailSendException("SMTP connection timed out"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        CreateNotificationRequest request = new CreateNotificationRequest(
                "falha@dominio.com.br",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Usuário Falha")
        );

        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.sentAt").doesNotExist())
                .andExpect(jsonPath("$.lastError").value("SMTP connection timed out"))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(responseJson.get("id").asText());

        Optional<NotificationEntity> inDb = notificationRepository.findById(id);
        assertThat(inDb).isPresent();
        assertThat(inDb.get().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(inDb.get().getAttempts()).isEqualTo(1);
        assertThat(inDb.get().getLastError()).contains("SMTP connection timed out");
    }
}
