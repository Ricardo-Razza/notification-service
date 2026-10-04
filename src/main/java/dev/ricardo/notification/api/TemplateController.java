package dev.ricardo.notification.api;

import dev.ricardo.notification.application.TemplateService;
import dev.ricardo.notification.domain.NotificationChannel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping
    public ResponseEntity<TemplateResponse> createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        TemplateResponse created = templateService.createTemplate(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<TemplateResponse> listTemplates(
            @RequestParam(name = "channel", required = false) NotificationChannel channel
    ) {
        return templateService.listTemplates(channel);
    }

    @GetMapping("/{id}")
    public TemplateResponse getTemplateById(@PathVariable("id") UUID id) {
        return templateService.getTemplateById(id);
    }

    @PutMapping("/{id}")
    public TemplateResponse updateTemplate(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateTemplateRequest request
    ) {
        return templateService.updateTemplate(id, request);
    }
}
