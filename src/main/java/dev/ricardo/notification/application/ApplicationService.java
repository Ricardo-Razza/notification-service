package dev.ricardo.notification.application;

import dev.ricardo.notification.api.admin.ApplicationCreatedResponse;
import dev.ricardo.notification.api.admin.ApplicationResponse;
import dev.ricardo.notification.api.admin.CreateApplicationRequest;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.ApplicationNotFoundException;
import dev.ricardo.notification.domain.DuplicateApplicationNameException;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ApiKeyService apiKeyService;
    @org.springframework.beans.factory.annotation.Value("${app.admin.key-grace-hours:24}") private long keyGraceHours = 24;

    @Transactional
    public ApplicationCreatedResponse rotateKey(UUID id) {
        var entity = applicationRepository.findLockedById(id).orElseThrow(() -> new ApplicationNotFoundException(id));
        var key = apiKeyService.generateApiKey();
        entity.setApiKeyHashPrevious(entity.getApiKeyHash());
        entity.setPreviousKeyExpiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(keyGraceHours)));
        entity.setApiKeyHash(key.hash());
        return ApplicationCreatedResponse.of(applicationRepository.save(entity), key.rawApiKey());
    }

    @Transactional
    public ApplicationResponse revokePreviousKey(UUID id) {
        var entity = applicationRepository.findLockedById(id).orElseThrow(() -> new ApplicationNotFoundException(id));
        entity.setApiKeyHashPrevious(null); entity.setPreviousKeyExpiresAt(null);
        return ApplicationResponse.from(applicationRepository.save(entity));
    }

    @Transactional
    public ApplicationResponse updateRateLimit(UUID id, Integer limit) {
        if (limit != null && limit < 1) throw new dev.ricardo.notification.domain.InvalidNotificationException("Rate limit must be positive or null");
        var entity = applicationRepository.findLockedById(id).orElseThrow(() -> new ApplicationNotFoundException(id));
        entity.setRateLimitPerMinute(limit); return ApplicationResponse.from(applicationRepository.save(entity));
    }

    @Transactional
    public ApplicationCreatedResponse createApplication(CreateApplicationRequest request) {
        String trimmedName = request.name().trim();
        if (applicationRepository.existsByName(trimmedName)) {
            throw new DuplicateApplicationNameException(trimmedName);
        }

        ApiKeyService.GeneratedApiKey generatedKey = apiKeyService.generateApiKey();

        ApplicationEntity entity = ApplicationEntity.builder()
                .name(trimmedName)
                .apiKeyHash(generatedKey.hash())
                .active(true)
                .build();

        ApplicationEntity saved = applicationRepository.save(entity);
        log.info("Registered application {} with name '{}'", saved.getId(), saved.getName());
        return ApplicationCreatedResponse.of(saved, generatedKey.rawApiKey());
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> listApplications() {
        return applicationRepository.findAll().stream()
                .map(ApplicationResponse::from)
                .toList();
    }

    @Transactional
    public ApplicationResponse deactivate(UUID id) {
        ApplicationEntity entity = applicationRepository.findById(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
        entity.setActive(false);
        ApplicationEntity saved = applicationRepository.save(entity);
        log.info("Deactivated application {}", saved.getId());
        return ApplicationResponse.from(saved);
    }

    @Transactional
    public ApplicationResponse activate(UUID id) {
        ApplicationEntity entity = applicationRepository.findById(id)
                .orElseThrow(() -> new ApplicationNotFoundException(id));
        entity.setActive(true);
        ApplicationEntity saved = applicationRepository.save(entity);
        log.info("Activated application {}", saved.getId());
        return ApplicationResponse.from(saved);
    }
}
