package dev.ricardo.notification.api;

import dev.ricardo.notification.application.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<NotificationResponse> sendNotification(
            @org.springframework.web.bind.annotation.RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateNotificationRequest request
    ) {
        SendNotificationResult result = notificationService.sendNotification(request, applicationId, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .body(result.response());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.response().id())
                .toUri();
        return ResponseEntity.accepted().location(location).body(result.response());
    }

    @GetMapping
    public PageResponse<NotificationResponse> getNotifications(
            @org.springframework.web.bind.annotation.RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(value = "channel", required = false) String channel,
            @org.springframework.web.bind.annotation.RequestParam(value = "recipient", required = false) String recipient,
            @org.springframework.web.bind.annotation.RequestParam(value = "createdFrom", required = false) String createdFrom,
            @org.springframework.web.bind.annotation.RequestParam(value = "createdTo", required = false) String createdTo,
            @org.springframework.web.bind.annotation.RequestParam(value = "page", defaultValue = "0") Integer page,
            @org.springframework.web.bind.annotation.RequestParam(value = "size", defaultValue = "20") Integer size
    ) {
        return notificationService.getNotifications(
                applicationId,
                status,
                channel,
                recipient,
                createdFrom,
                createdTo,
                page,
                size
        );
    }

    @GetMapping("/{id}")
    public NotificationResponse getNotificationById(
            @org.springframework.web.bind.annotation.RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        return notificationService.getNotificationById(id, applicationId);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<NotificationResponse> retryNotification(
            @org.springframework.web.bind.annotation.RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        NotificationResponse response = notificationService.retryNotification(id, applicationId);
        return ResponseEntity.accepted().body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<NotificationResponse> cancelNotification(
            @org.springframework.web.bind.annotation.RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        NotificationResponse response = notificationService.cancelScheduledNotification(id, applicationId);
        return ResponseEntity.ok(response);
    }
}
