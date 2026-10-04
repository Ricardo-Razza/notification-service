package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.ApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationAdminController.class)
@TestPropertySource(properties = "app.admin.token=secret-admin-token-123")
class ApplicationAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApplicationService applicationService;

    @Test
    @DisplayName("POST /api/v1/admin/applications com X-Admin-Token válido deve criar aplicação e retornar 201 com chave")
    void shouldCreateApplicationSuccessfully() throws Exception {
        UUID id = UUID.randomUUID();
        CreateApplicationRequest request = new CreateApplicationRequest("Order Service");
        ApplicationCreatedResponse response = new ApplicationCreatedResponse(
                id,
                "Order Service",
                "ntf_plain_secret_key_123",
                true,
                Instant.now()
        );

        when(applicationService.createApplication(any(CreateApplicationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/applications")
                        .header("X-Admin-Token", "secret-admin-token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.name", is("Order Service")))
                .andExpect(jsonPath("$.apiKey", is("ntf_plain_secret_key_123")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    @DisplayName("POST /api/v1/admin/applications sem X-Admin-Token deve responder 401 ProblemDetail")
    void shouldRejectWhenAdminTokenIsMissing() throws Exception {
        CreateApplicationRequest request = new CreateApplicationRequest("Order Service");

        mockMvc.perform(post("/api/v1/admin/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", is("urn:problem-type:unauthorized")))
                .andExpect(jsonPath("$.title", is("Unauthorized")));
    }

    @Test
    @DisplayName("POST /api/v1/admin/applications com X-Admin-Token inválido deve responder 401 ProblemDetail")
    void shouldRejectWhenAdminTokenIsInvalid() throws Exception {
        CreateApplicationRequest request = new CreateApplicationRequest("Order Service");

        mockMvc.perform(post("/api/v1/admin/applications")
                        .header("X-Admin-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", is("urn:problem-type:unauthorized")));
    }

    @Test
    @DisplayName("GET /api/v1/admin/applications deve listar aplicações quando autenticado")
    void shouldListApplications() throws Exception {
        UUID id = UUID.randomUUID();
        ApplicationResponse app = new ApplicationResponse(id, "App", true, Instant.now(), Instant.now());
        when(applicationService.listApplications()).thenReturn(List.of(app));

        mockMvc.perform(get("/api/v1/admin/applications")
                        .header("X-Admin-Token", "secret-admin-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("App")));
    }

    @Test
    @DisplayName("POST /api/v1/admin/applications/{id}/deactivate deve desativar aplicação")
    void shouldDeactivateApplication() throws Exception {
        UUID id = UUID.randomUUID();
        ApplicationResponse app = new ApplicationResponse(id, "App", false, Instant.now(), Instant.now());
        when(applicationService.deactivate(id)).thenReturn(app);

        mockMvc.perform(post("/api/v1/admin/applications/{id}/deactivate", id)
                        .header("X-Admin-Token", "secret-admin-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));
    }
}
