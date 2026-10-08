package dev.ricardo.notification.api;

import dev.ricardo.notification.application.TemplateService;
import dev.ricardo.notification.domain.DuplicateTemplateException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TemplateController.class)
class TemplateControllerTest {

    private static final UUID APPLICATION_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TemplateService templateService;

    @Test
    @DisplayName("POST /api/v1/templates deve retornar 201 Created quando o payload for válido")
    void shouldCreateTemplateSuccessfully() throws Exception {
        UUID id = UUID.randomUUID();
        CreateTemplateRequest request = new CreateTemplateRequest(
                "ORDER_CONFIRMATION",
                NotificationChannel.EMAIL,
                "Pedido confirmado",
                "Seu pedido {{orderId}} foi confirmado."
        );

        TemplateResponse response = new TemplateResponse(
                id,
                "ORDER_CONFIRMATION",
                NotificationChannel.EMAIL,
                "Pedido confirmado",
                "Seu pedido {{orderId}} foi confirmado.",
                Instant.now(),
                Instant.now()
        );

        when(templateService.createTemplate(any(CreateTemplateRequest.class), eq(APPLICATION_ID))).thenReturn(response);

        mockMvc.perform(post("/api/v1/templates")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/templates/" + id)))
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.code", is("ORDER_CONFIRMATION")))
                .andExpect(jsonPath("$.channel", is("EMAIL")));
    }

    @Test
    @DisplayName("POST /api/v1/templates deve retornar 400 Bad Request em formato ProblemDetail se code for inválido")
    void shouldReturnBadRequestWhenCodeIsInvalid() throws Exception {
        CreateTemplateRequest invalidRequest = new CreateTemplateRequest(
                "invalid-code-lowercase",
                NotificationChannel.EMAIL,
                "Subject",
                "Content"
        );

        mockMvc.perform(post("/api/v1/templates")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", is("urn:problem-type:validation-error")))
                .andExpect(jsonPath("$.title", is("Validation Error")))
                .andExpect(jsonPath("$.errors.code").exists());
    }

    @Test
    @DisplayName("POST /api/v1/templates deve retornar 409 Conflict em formato ProblemDetail para duplicatas")
    void shouldReturnConflictWhenDuplicate() throws Exception {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "EXISTING_CODE",
                NotificationChannel.SMS,
                null,
                "Content"
        );

        when(templateService.createTemplate(any(CreateTemplateRequest.class), eq(APPLICATION_ID)))
                .thenThrow(new DuplicateTemplateException("EXISTING_CODE", NotificationChannel.SMS));

        mockMvc.perform(post("/api/v1/templates")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", is("urn:problem-type:conflict")))
                .andExpect(jsonPath("$.title", is("Conflict")))
                .andExpect(jsonPath("$.detail", containsString("already exists")));
    }

    @Test
    @DisplayName("GET /api/v1/templates deve listar templates com filtro opcional por canal")
    void shouldListTemplates() throws Exception {
        TemplateResponse template = new TemplateResponse(
                UUID.randomUUID(),
                "PUSH_PROMO",
                NotificationChannel.PUSH,
                null,
                "Oferta relâmpago!",
                Instant.now(),
                Instant.now()
        );

        when(templateService.listTemplates(NotificationChannel.PUSH, APPLICATION_ID)).thenReturn(List.of(template));

        mockMvc.perform(get("/api/v1/templates").param("channel", "PUSH")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code", is("PUSH_PROMO")))
                .andExpect(jsonPath("$[0].channel", is("PUSH")));
    }

    @Test
    @DisplayName("GET /api/v1/templates/{id} deve retornar 200 OK quando o template existir")
    void shouldGetTemplateById() throws Exception {
        UUID id = UUID.randomUUID();
        TemplateResponse template = new TemplateResponse(
                id,
                "WELCOME",
                NotificationChannel.EMAIL,
                "Bem-vindo",
                "Olá!",
                Instant.now(),
                Instant.now()
        );

        when(templateService.getTemplateById(id, APPLICATION_ID)).thenReturn(template);

        mockMvc.perform(get("/api/v1/templates/{id}", id)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.code", is("WELCOME")));
    }

    @Test
    @DisplayName("GET /api/v1/templates/{id} deve retornar 404 Not Found em formato ProblemDetail")
    void shouldReturnNotFoundWhenTemplateDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.getTemplateById(id, APPLICATION_ID)).thenThrow(new TemplateNotFoundException(id));

        mockMvc.perform(get("/api/v1/templates/{id}", id)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", is("urn:problem-type:not-found")))
                .andExpect(jsonPath("$.title", is("Resource Not Found")))
                .andExpect(jsonPath("$.detail", containsString(id.toString())));
    }

    @Test
    @DisplayName("PUT /api/v1/templates/{id} deve retornar 200 OK ao atualizar subject e content")
    void shouldUpdateTemplateSuccessfully() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateTemplateRequest updateRequest = new UpdateTemplateRequest("Novo Assunto", "Novo Conteúdo");

        TemplateResponse updatedResponse = new TemplateResponse(
                id,
                "WELCOME",
                NotificationChannel.EMAIL,
                "Novo Assunto",
                "Novo Conteúdo",
                Instant.now(),
                Instant.now()
        );

        when(templateService.updateTemplate(eq(id), any(UpdateTemplateRequest.class), eq(APPLICATION_ID)))
                .thenReturn(updatedResponse);

        mockMvc.perform(put("/api/v1/templates/{id}", id)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, APPLICATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.subject", is("Novo Assunto")))
                .andExpect(jsonPath("$.content", is("Novo Conteúdo")));
    }
}
