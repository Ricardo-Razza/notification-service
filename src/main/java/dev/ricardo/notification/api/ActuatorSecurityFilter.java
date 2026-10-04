package dev.ricardo.notification.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;

public class ActuatorSecurityFilter extends OncePerRequestFilter {

    private final String adminToken;
    private final ObjectMapper objectMapper;

    public ActuatorSecurityFilter(String adminToken, ObjectMapper objectMapper) {
        this.adminToken = (adminToken != null) ? adminToken.trim() : "";
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // Endpoints públicos de diagnóstico: health e info
        if (isPublicActuatorEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Se ADMIN_TOKEN não estiver configurado, métricas ficam desabilitadas por segurança (403 Forbidden)
        if (adminToken.isEmpty()) {
            sendProblemDetail(
                    response,
                    HttpStatus.FORBIDDEN,
                    "urn:problem-type:forbidden",
                    "Forbidden",
                    "Actuator metrics and management endpoints are disabled because ADMIN_TOKEN is not configured"
            );
            return;
        }

        String token = request.getHeader("X-Admin-Token");
        if (token == null || !constantTimeEquals(token, adminToken)) {
            sendProblemDetail(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "urn:problem-type:unauthorized",
                    "Unauthorized",
                    "Invalid or missing X-Admin-Token for actuator metrics"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicActuatorEndpoint(String path) {
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info") || path.startsWith("/actuator/info/");
    }

    private void sendProblemDetail(HttpServletResponse response, HttpStatus status, String type, String title, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
