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

        Map<String, String> variables,

        String scheduledAt
) {
    public CreateNotificationRequest(
            String recipient,
            NotificationChannel channel,
            String template,
            Map<String, String> variables
    ) {
        this(recipient, channel, template, variables, null);
    }

    public Map<String, String> safeVariables() {
        return variables != null ? variables : Map.of();
    }
}
