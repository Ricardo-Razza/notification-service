package dev.ricardo.notification.api;

import dev.ricardo.notification.domain.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTemplateRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 100, message = "Code must not exceed 100 characters")
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "Code must contain only uppercase letters, numbers and underscores")
        String code,

        @NotNull(message = "Channel is required")
        NotificationChannel channel,

        @Size(max = 255, message = "Subject must not exceed 255 characters")
        String subject,

        @NotBlank(message = "Content is required")
        String content
) {
}
