package dev.ricardo.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTemplateRequest(
        @Size(max = 255, message = "Subject must not exceed 255 characters")
        String subject,

        @NotBlank(message = "Content is required")
        String content
) {
}
