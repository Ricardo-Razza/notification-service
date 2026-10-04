package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.ApplicationService;
import dev.ricardo.notification.domain.AdminDisabledException;
import dev.ricardo.notification.domain.InvalidAdminTokenException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/applications")
public class ApplicationAdminController {

    private final ApplicationService applicationService;
    private final String adminToken;

    public ApplicationAdminController(
            ApplicationService applicationService,
            @Value("${app.admin.token:}") String adminToken
    ) {
        this.applicationService = applicationService;
        this.adminToken = (adminToken != null) ? adminToken.trim() : "";
    }

    @PostMapping
    public ResponseEntity<ApplicationCreatedResponse> createApplication(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody CreateApplicationRequest request
    ) {
        authenticateAdmin(token);
        ApplicationCreatedResponse response = applicationService.createApplication(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public List<ApplicationResponse> listApplications(
            @RequestHeader(value = "X-Admin-Token", required = false) String token
    ) {
        authenticateAdmin(token);
        return applicationService.listApplications();
    }

    @PostMapping("/{id}/deactivate")
    public ApplicationResponse deactivate(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable("id") UUID id
    ) {
        authenticateAdmin(token);
        return applicationService.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public ApplicationResponse activate(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable("id") UUID id
    ) {
        authenticateAdmin(token);
        return applicationService.activate(id);
    }

    private void authenticateAdmin(String token) {
        if (adminToken.isEmpty()) {
            throw new AdminDisabledException("Admin endpoints are disabled because ADMIN_TOKEN is not configured");
        }
        if (token == null || !constantTimeEquals(token, adminToken)) {
            throw new InvalidAdminTokenException("Invalid or missing X-Admin-Token");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
