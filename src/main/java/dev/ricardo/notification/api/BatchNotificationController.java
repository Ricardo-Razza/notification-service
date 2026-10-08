package dev.ricardo.notification.api;

import dev.ricardo.notification.application.NotificationService;
import dev.ricardo.notification.application.NotificationRateLimiter;
import dev.ricardo.notification.domain.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/notifications/batch")
@io.swagger.v3.oas.annotations.tags.Tag(name="Notifications")
public class BatchNotificationController {
    private final NotificationService service;
    private final NotificationRateLimiter rateLimiter;
    private final Validator validator;
    @Value("${app.limits.batch-size:100}") private int maxItems;
    public record Item(String recipient, NotificationChannel channel, String template, Map<String, String> variables,
                       String scheduledAt, String idempotencyKey) {
        CreateNotificationRequest request() { return new CreateNotificationRequest(recipient, channel, template, variables, scheduledAt); }
    }
    public record Request(List<Item> items) {}
    public record Result(String status, UUID id, ProblemDetail error) {}
    @PostMapping
    @io.swagger.v3.oas.annotations.Operation(summary="Create up to the configured number of notifications with per-item results")
    public List<Result> send(@RequestBody Request request,
                            @RequestAttribute(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID) UUID applicationId) {
        if (request.items() == null || request.items().isEmpty() || request.items().size() > maxItems)
            throw new InvalidNotificationException("Batch must contain between 1 and " + maxItems + " items");
        rateLimiter.reserve(applicationId, request.items().size());
        List<Result> results = new ArrayList<>();
        for (Item item : request.items()) {
            try {
                if (item == null || !validator.validate(item.request()).isEmpty()) throw new InvalidNotificationException("Invalid batch item");
                var result = service.sendNotification(item.request(), applicationId, item.idempotencyKey(), false);
                results.add(new Result(result.replayed() ? "replayed" : "created", result.response().id(), null));
            } catch (RuntimeException ex) {
                HttpStatus status = ex instanceof TemplateNotFoundException ? HttpStatus.NOT_FOUND
                        : ex instanceof IdempotencyConflictException || ex instanceof UnsupportedChannelException || ex instanceof dev.ricardo.notification.template.MissingTemplateVariableException ? HttpStatus.UNPROCESSABLE_ENTITY
                        : ex instanceof NotificationPublishException ? HttpStatus.SERVICE_UNAVAILABLE
                        : ex instanceof InvalidNotificationException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
                String detail = status == HttpStatus.INTERNAL_SERVER_ERROR ? "Unable to process batch item" : ex.getMessage();
                results.add(new Result("failed", null, ProblemDetail.forStatusAndDetail(status, detail)));
            }
        }
        return results;
    }
}
