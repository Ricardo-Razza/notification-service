package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Collections;
import java.util.Map;

public record UpdateScheduleRequest(
        @NotBlank(message = "Name cannot be empty")
        @Size(max = 100, message = "Name must have at most 100 characters")
        String name,

        @NotBlank(message = "Cron expression cannot be empty")
        @Size(max = 100, message = "Cron expression must have at most 100 characters")
        String cron,

        @NotBlank(message = "Timezone cannot be empty")
        @Size(max = 64, message = "Timezone must have at most 64 characters")
        String timezone,

        @NotNull(message = "Channel is required")
        NotificationChannel channel,

        @NotBlank(message = "Recipient cannot be empty")
        @Size(max = 320, message = "Recipient must have at most 320 characters")
        String recipient,

        @NotBlank(message = "Template cannot be empty")
        @Size(max = 100, message = "Template must have at most 100 characters")
        String template,

        Map<String, String> variables,

        String startAt,

        String endAt
) {
    public Map<String, String> safeVariables() {
        return variables != null ? variables : Collections.emptyMap();
    }
}
