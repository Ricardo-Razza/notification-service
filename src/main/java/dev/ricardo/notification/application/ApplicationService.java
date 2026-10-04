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
