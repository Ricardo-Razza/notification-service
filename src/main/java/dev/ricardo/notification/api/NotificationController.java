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
            @Valid @RequestBody CreateNotificationRequest request
    ) {
        NotificationResponse response = notificationService.sendNotification(request, applicationId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public NotificationResponse getNotificationById(@PathVariable("id") UUID id) {
        return notificationService.getNotificationById(id);
    }
}
