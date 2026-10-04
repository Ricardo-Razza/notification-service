package dev.ricardo.notification.api;

import dev.ricardo.notification.application.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
        return ResponseEntity.created(location).body(result.response());
    }

    @GetMapping("/{id}")
    public NotificationResponse getNotificationById(@PathVariable("id") UUID id) {
        return notificationService.getNotificationById(id);
    }
}
