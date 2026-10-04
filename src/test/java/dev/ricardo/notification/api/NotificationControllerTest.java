package dev.ricardo.notification.api;

import dev.ricardo.notification.application.NotificationService;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationNotFoundException;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.domain.UnsupportedChannelException;
import dev.ricardo.notification.template.MissingTemplateVariableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @DisplayName("POST /api/v1/notifications deve responder 201 com status SENT e payload correto")
    void shouldCreateAndSendNotificationSuccessfully() throws Exception {
        UUID id = UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo")
        );

        NotificationResponse response = new NotificationResponse(
                id,
                NotificationStatus.SENT,
                NotificationChannel.EMAIL,
                "ricardo@exemplo.com",
                "WELCOME",
                1,
                Instant.now(),
                Instant.now(),
                null
        );

        when(notificationService.sendNotification(any(CreateNotificationRequest.class), any(), any()))
                .thenReturn(new SendNotificationResult(response, false));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/notifications/" + id)))
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.status", is("SENT")))
                .andExpect(jsonPath("$.channel", is("EMAIL")))
                .andExpect(jsonPath("$.recipient", is("ricardo@exemplo.com")))
                .andExpect(jsonPath("$.template", is("WELCOME")))
                .andExpect(jsonPath("$.attempts", is(1)))
                .andExpect(jsonPath("$.sentAt").exists())
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/notifications com Idempotency-Key repetida deve responder 200 com header Idempotent-Replayed: true")
    void shouldReturnOkWithIdempotentReplayedHeaderWhenKeyAlreadyExists() throws Exception {
        UUID id = UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo")
        );

        NotificationResponse response = new NotificationResponse(
                id,
                NotificationStatus.SENT,
                NotificationChannel.EMAIL,
                "ricardo@exemplo.com",
                "WELCOME",
                1,
                Instant.now(),
                Instant.now(),
                null
        );

        when(notificationService.sendNotification(any(CreateNotificationRequest.class), any(), any()))
                .thenReturn(new SendNotificationResult(response, true));

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Idempotency-Key", "req-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.status", is("SENT")));
    }

    @Test
    @DisplayName("POST /api/v1/notifications deve responder 400 Bad Request se recipient estiver em branco")
    void shouldReturnBadRequestWhenRecipientIsMissing() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of()
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", is("urn:problem-type:validation-error")))
                .andExpect(jsonPath("$.errors.recipient").exists());
    }

    @Test
    @DisplayName("POST /api/v1/notifications deve responder 404 Not Found se template não existir")
    void shouldReturnNotFoundWhenTemplateDoesNotExist() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "UNKNOWN",
                Map.of()
        );

        when(notificationService.sendNotification(any(CreateNotificationRequest.class), any(), any()))
                .thenThrow(new TemplateNotFoundException("Template not found"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", is("urn:problem-type:not-found")))
                .andExpect(jsonPath("$.title", is("Resource Not Found")));
    }

    @Test
    @DisplayName("POST /api/v1/notifications deve responder 422 se faltar variável obrigatória no template")
    void shouldReturnUnprocessableEntityWhenVariableIsMissing() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of()
        );

        when(notificationService.sendNotification(any(CreateNotificationRequest.class), any(), any()))
                .thenThrow(new MissingTemplateVariableException("name"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", is("urn:problem-type:missing-variable")))
                .andExpect(jsonPath("$.title", is("Missing Template Variable")))
                .andExpect(jsonPath("$.detail", containsString("name")));
    }

    @Test
    @DisplayName("POST /api/v1/notifications deve responder 422 se o canal não possuir sender configurado")
    void shouldReturnUnprocessableEntityWhenChannelNotSupported() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "+5511999999999",
                NotificationChannel.SMS,
                "ALERT",
                Map.of()
        );

        when(notificationService.sendNotification(any(CreateNotificationRequest.class), any(), any()))
                .thenThrow(new UnsupportedChannelException(NotificationChannel.SMS));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", is("urn:problem-type:unsupported-channel")))
                .andExpect(jsonPath("$.title", is("Unsupported Channel")));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} deve responder 200 com os dados da notificação")
    void shouldGetNotificationById() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationResponse response = new NotificationResponse(
                id,
                NotificationStatus.SENT,
                NotificationChannel.EMAIL,
                "ricardo@exemplo.com",
                "WELCOME",
                1,
                Instant.now(),
                Instant.now(),
                null
        );

        when(notificationService.getNotificationById(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.status", is("SENT")));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} deve responder 404 quando não existir")
    void shouldReturnNotFoundWhenNotificationDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(notificationService.getNotificationById(id))
                .thenThrow(new NotificationNotFoundException(id));

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", is("urn:problem-type:not-found")));
    }
}
