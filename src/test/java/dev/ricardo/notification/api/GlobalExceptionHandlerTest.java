package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.DuplicateTemplateException;
import dev.ricardo.notification.domain.InvalidNotificationException;
import dev.ricardo.notification.domain.InvalidTemplateException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationNotFoundException;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.domain.UnsupportedChannelException;
import dev.ricardo.notification.template.MissingTemplateVariableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleTemplateNotFound deve retornar ProblemDetail 404 sem stack trace")
    void shouldHandleTemplateNotFound() {
        UUID id = UUID.randomUUID();
        ProblemDetail problem = handler.handleTemplateNotFound(new TemplateNotFoundException(id));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:not-found");
        assertThat(problem.getDetail()).contains(id.toString());
        assertThat(problem.getProperties()).containsKey("timestamp");
        assertThat(problem.getProperties()).doesNotContainKey("stackTrace");
    }

    @Test
    @DisplayName("handleNotificationNotFound deve retornar ProblemDetail 404 sem stack trace")
    void shouldHandleNotificationNotFound() {
        UUID id = UUID.randomUUID();
        ProblemDetail problem = handler.handleNotificationNotFound(new NotificationNotFoundException(id));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:not-found");
        assertThat(problem.getDetail()).contains(id.toString());
        assertThat(problem.getProperties()).containsKey("timestamp");
        assertThat(problem.getProperties()).doesNotContainKey("stackTrace");
    }

    @Test
    @DisplayName("handleDuplicateTemplate deve retornar ProblemDetail 409 sem stack trace")
    void shouldHandleDuplicateTemplate() {
        ProblemDetail problem = handler.handleDuplicateTemplate(new DuplicateTemplateException("CODE", NotificationChannel.EMAIL));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:conflict");
        assertThat(problem.getProperties()).containsKey("timestamp");
        assertThat(problem.getProperties()).doesNotContainKey("stackTrace");
    }

    @Test
    @DisplayName("handleInvalidNotification deve retornar ProblemDetail 400 sem stack trace")
    void shouldHandleInvalidNotification() {
        ProblemDetail problem = handler.handleInvalidNotification(new InvalidNotificationException("Recipient blank"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid Request");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:bad-request");
        assertThat(problem.getDetail()).isEqualTo("Recipient blank");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleInvalidTemplate deve retornar ProblemDetail 400 sem stack trace")
    void shouldHandleInvalidTemplate() {
        ProblemDetail problem = handler.handleInvalidTemplate(new InvalidTemplateException("Subject required"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid Request");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:bad-request");
        assertThat(problem.getDetail()).isEqualTo("Subject required");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleMissingTemplateVariable deve retornar ProblemDetail 422 com o nome da variável")
    void shouldHandleMissingTemplateVariable() {
        ProblemDetail problem = handler.handleMissingTemplateVariable(new MissingTemplateVariableException("user_name"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(problem.getTitle()).isEqualTo("Missing Template Variable");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:missing-variable");
        assertThat(problem.getDetail()).contains("user_name");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleUnsupportedChannel deve retornar ProblemDetail 422 sem stack trace")
    void shouldHandleUnsupportedChannel() {
        ProblemDetail problem = handler.handleUnsupportedChannel(new UnsupportedChannelException(NotificationChannel.SMS));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(problem.getTitle()).isEqualTo("Unsupported Channel");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:unsupported-channel");
        assertThat(problem.getDetail()).contains("SMS");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleDataIntegrityViolation deve retornar ProblemDetail 409 genérico")
    void shouldHandleDataIntegrityViolation() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate key"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:conflict");
        assertThat(problem.getDetail()).isEqualTo("A resource with the specified identifiers already exists");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleMessageNotReadable deve retornar ProblemDetail 400")
    void shouldHandleMessageNotReadable() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null);
        ProblemDetail problem = handler.handleMessageNotReadable(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Malformed Request");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:bad-request");
        assertThat(problem.getDetail()).isEqualTo("Malformed JSON request body");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("handleGenericException deve retornar ProblemDetail 500 sem stack trace no body")
    void shouldHandleGenericException() {
        Exception ex = new RuntimeException("Fatal database connection crash: sensitive credentials root:pass@host");
        ProblemDetail problem = handler.handleGenericException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getType().toString()).isEqualTo("urn:problem-type:internal-server-error");
        // O detalhe DEVE ser genérico, nunca vazando a mensagem ou stack trace da exceção interna
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(problem.getDetail()).doesNotContain("sensitive credentials");
        assertThat(problem.getProperties()).containsKey("timestamp");
        assertThat(problem.getProperties()).doesNotContainKey("stackTrace");
    }
}
