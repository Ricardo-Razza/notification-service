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

        when(notificationService.getNotificationById(org.mockito.ArgumentMatchers.eq(id), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.status", is("SENT")));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} deve responder 404 quando não existir")
    void shouldReturnNotFoundWhenNotificationDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(notificationService.getNotificationById(org.mockito.ArgumentMatchers.eq(id), any()))
                .thenThrow(new NotificationNotFoundException(id));

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", is("urn:problem-type:not-found")));
    }

    @Test
    @DisplayName("GET /api/v1/notifications deve retornar lista paginada com status 200")
    void shouldReturnPaginatedNotifications() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationResponse item = new NotificationResponse(
                id,
                NotificationStatus.SENT,
                NotificationChannel.EMAIL,
                "user@exemplo.com",
                "WELCOME",
                1,
                Instant.now(),
                Instant.now(),
                null
        );

        PageResponse<NotificationResponse> pageResponse = new PageResponse<>(
                java.util.List.of(item),
                0,
                20,
                1L,
                1
        );

        when(notificationService.getNotifications(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/notifications")
                        .param("status", "SENT")
                        .param("channel", "EMAIL")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(id.toString())))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/notifications com filtro inválido deve retornar 400 Bad Request")
    void shouldReturnBadRequestWhenFilterIsInvalid() throws Exception {
        when(notificationService.getNotifications(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new dev.ricardo.notification.domain.InvalidNotificationException("Invalid status: 'INVALID'"));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", is("urn:problem-type:bad-request")))
                .andExpect(jsonPath("$.detail", containsString("Invalid status")));
    }
}
