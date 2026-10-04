package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateNotificationRequest(
        @NotBlank(message = "Recipient is required")
        String recipient,

        @NotNull(message = "Channel is required")
        NotificationChannel channel,

        @NotBlank(message = "Template is required")
        String template,

        Map<String, String> variables
) {
    public Map<String, String> safeVariables() {
        return variables != null ? variables : Map.of();
    }
}
