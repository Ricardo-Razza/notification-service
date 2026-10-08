package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateTemplateRequest;
import dev.ricardo.notification.api.TemplateResponse;
import dev.ricardo.notification.api.UpdateTemplateRequest;
import dev.ricardo.notification.domain.DuplicateTemplateException;
import dev.ricardo.notification.domain.InvalidTemplateException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final InputLimits inputLimits;

    @Transactional
    public TemplateResponse createTemplate(CreateTemplateRequest request) {
        return createTemplate(request, null);
    }

    @Transactional
    public TemplateResponse createTemplate(CreateTemplateRequest request, UUID applicationId) {
        inputLimits.template(request.subject(), request.content());
        validateSubjectForEmail(request.channel(), request.subject());

        boolean exists = applicationId == null
                ? templateRepository.existsByCodeAndChannel(request.code(), request.channel())
                : templateRepository.existsByApplicationIdAndCodeAndChannel(applicationId, request.code(), request.channel());
        if (exists) {
            throw new DuplicateTemplateException(request.code(), request.channel());
        }

        TemplateEntity entity = TemplateEntity.builder()
                .applicationId(applicationId)
                .code(request.code())
                .channel(request.channel())
                .subject(request.subject())
                .content(request.content())
                .build();

        TemplateEntity saved = templateRepository.save(entity);
        return TemplateResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public TemplateResponse getTemplateById(UUID id) {
        return templateRepository.findById(id)
                .map(TemplateResponse::from)
                .orElseThrow(() -> new TemplateNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public TemplateResponse getTemplateById(UUID id, UUID applicationId) {
        return TemplateResponse.from(getVisible(id, applicationId));
    }

    private TemplateEntity getVisible(UUID id, UUID applicationId) {
        TemplateEntity entity = templateRepository.findById(id).orElseThrow(() -> new TemplateNotFoundException(id));
        if (entity.getApplicationId() != null && !entity.getApplicationId().equals(applicationId)) {
            throw new TemplateNotFoundException(id);
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> listTemplates(NotificationChannel channel, UUID applicationId) {
        return templateRepository.findVisibleTemplates(applicationId, channel).stream().map(TemplateResponse::from).toList();
    }

    @Transactional
    public TemplateResponse updateTemplate(UUID id, UpdateTemplateRequest request, UUID applicationId) {
        inputLimits.template(request.subject(), request.content());
        TemplateEntity entity = getVisible(id, applicationId);
        if (entity.getApplicationId() == null) throw new dev.ricardo.notification.domain.GlobalTemplateReadOnlyException();
        validateSubjectForEmail(entity.getChannel(), request.subject());
        entity.setSubject(request.subject());
        entity.setContent(request.content());
        return TemplateResponse.from(templateRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> listTemplates(NotificationChannel channel) {
        List<TemplateEntity> templates = (channel != null)
                ? templateRepository.findByChannel(channel)
                : templateRepository.findAll();

        return templates.stream()
                .map(TemplateResponse::from)
                .toList();
    }

    @Transactional
    public TemplateResponse updateTemplate(UUID id, UpdateTemplateRequest request) {
        inputLimits.template(request.subject(), request.content());
        TemplateEntity entity = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException(id));

        validateSubjectForEmail(entity.getChannel(), request.subject());

        entity.setSubject(request.subject());
        entity.setContent(request.content());

        TemplateEntity saved = templateRepository.save(entity);
        return TemplateResponse.from(saved);
    }

    private void validateSubjectForEmail(NotificationChannel channel, String subject) {
        if (channel == NotificationChannel.EMAIL && (subject == null || subject.isBlank())) {
            throw new InvalidTemplateException("Subject is required for EMAIL channel");
        }
    }
}
