package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private FilterChain filterChain;

    private final ApiKeyService apiKeyService = new ApiKeyService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(apiKeyService, applicationRepository, objectMapper);
    }

    @Test
    @DisplayName("Deve rejeitar com 401 ProblemDetail quando header X-API-Key estiver ausente")
    void shouldReturn401WhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString()).contains("Invalid or missing API key");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve rejeitar com 401 ProblemDetail quando a chave for inválida")
    void shouldReturn401WhenKeyIsInvalid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
        request.addHeader("X-API-Key", "ntf_invalid_key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String hash = apiKeyService.hashApiKey("ntf_invalid_key");
        when(applicationRepository.findByApiKeyHash(hash)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or missing API key");
        // Não deve informar se a chave existe ou não
        assertThat(response.getContentAsString()).doesNotContain("not found");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve rejeitar com 401 ProblemDetail quando a aplicação estiver inativa")
    void shouldReturn401WhenApplicationIsInactive() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
        request.addHeader("X-API-Key", "ntf_inactive_key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String hash = apiKeyService.hashApiKey("ntf_inactive_key");
        ApplicationEntity inactiveApp = ApplicationEntity.builder()
                .id(UUID.randomUUID())
                .name("Inactive App")
                .apiKeyHash(hash)
                .active(false)
                .build();

        when(applicationRepository.findByApiKeyHash(hash)).thenReturn(Optional.of(inactiveApp));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        // Resposta não deve revelar que a aplicação está inativa
        assertThat(response.getContentAsString()).contains("Invalid or missing API key");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve permitir requisição e definir request attribute quando API Key for válida e ativa")
    void shouldAuthenticateActiveApplication() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/notifications");
        request.addHeader("X-API-Key", "ntf_valid_active_key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID appId = UUID.randomUUID();
        String hash = apiKeyService.hashApiKey("ntf_valid_active_key");
        ApplicationEntity app = ApplicationEntity.builder()
                .id(appId)
                .name("Valid App")
                .apiKeyHash(hash)
                .active(true)
                .build();

        when(applicationRepository.findByApiKeyHash(hash)).thenReturn(Optional.of(app));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID)).isEqualTo(appId);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("shouldNotFilter deve retornar true para /actuator e /api/v1/admin")
    void shouldNotFilterActuatorAndAdmin() {
        MockHttpServletRequest actuatorReq = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletRequest adminReq = new MockHttpServletRequest("POST", "/api/v1/admin/applications");
        MockHttpServletRequest notificationsReq = new MockHttpServletRequest("POST", "/api/v1/notifications");

        assertThat(filter.shouldNotFilter(actuatorReq)).isTrue();
        assertThat(filter.shouldNotFilter(adminReq)).isTrue();
        assertThat(filter.shouldNotFilter(notificationsReq)).isFalse();
    }
}
