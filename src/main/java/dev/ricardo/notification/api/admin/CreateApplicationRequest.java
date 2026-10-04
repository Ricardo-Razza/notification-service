package dev.ricardo.notification.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(
        @NotBlank(message = "Application name cannot be blank")
        @Size(max = 100, message = "Application name must have at most 100 characters")
        String name
) {}
