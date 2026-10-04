package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_APPLICATION_ID = "AUTHENTICATED_APPLICATION_ID";
    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ApplicationRepository applicationRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/api/v1/admin");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            writeUnauthorizedResponse(response, "Invalid or missing API key", request.getRequestURI());
            return;
        }

        String hash;
        try {
            hash = apiKeyService.hashApiKey(apiKey.trim());
        } catch (Exception e) {
            writeUnauthorizedResponse(response, "Invalid or missing API key", request.getRequestURI());
            return;
        }

        Optional<ApplicationEntity> appOptional = applicationRepository.findByApiKeyHash(hash);
        if (appOptional.isEmpty() || !appOptional.get().isActive()) {
            writeUnauthorizedResponse(response, "Invalid or missing API key", request.getRequestURI());
            return;
        }

        ApplicationEntity app = appOptional.get();
        request.setAttribute(AUTHENTICATED_APPLICATION_ID, app.getId());

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String detail, String uri) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("urn:problem-type:unauthorized"));
        problem.setInstance(URI.create(uri));
        problem.setProperty("timestamp", Instant.now());

        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
