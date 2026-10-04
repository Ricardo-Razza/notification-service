package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.api.NotificationResponse;
import dev.ricardo.notification.api.SendNotificationResult;
import dev.ricardo.notification.domain.InvalidNotificationException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationNotFoundException;
import dev.ricardo.notification.domain.NotificationSender;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.domain.RenderedMessage;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import dev.ricardo.notification.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final NotificationRepository notificationRepository;
    private final TemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final SenderFactory senderFactory;
    private final TransactionTemplate transactionTemplate;

    public NotificationResponse sendNotification(CreateNotificationRequest request) {
        return sendNotification(request, null, null).response();
    }

    public NotificationResponse sendNotification(CreateNotificationRequest request, UUID applicationId) {
        return sendNotification(request, applicationId, null).response();
    }

    public SendNotificationResult sendNotification(
            CreateNotificationRequest request,
            UUID applicationId,
            String idempotencyKey
    ) {
        String cleanIdempotencyKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey.trim()
                : null;

        if (cleanIdempotencyKey != null && cleanIdempotencyKey.length() > 255) {
            throw new InvalidNotificationException("Idempotency-Key header cannot exceed 255 characters");
        }

        if (cleanIdempotencyKey != null && applicationId != null) {
            Optional<NotificationEntity> existing = notificationRepository
                    .findByApplicationIdAndIdempotencyKey(applicationId, cleanIdempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent request replayed for notification {} with key '{}'", existing.get().getId(), cleanIdempotencyKey);
                return new SendNotificationResult(NotificationResponse.from(existing.get()), true);
            }
        }

        validateRecipient(request.channel(), request.recipient());

        TemplateEntity template = templateRepository.findByCodeAndChannel(request.template(), request.channel())
                .orElseThrow(() -> new TemplateNotFoundException(
                        String.format("Template '%s' not found for channel '%s'", request.template(), request.channel())
                ));

        NotificationSender sender = senderFactory.get(request.channel());

        Map<String, String> variables = request.safeVariables();
        String renderedSubject = template.getSubject() != null
                ? templateRenderer.render(template.getSubject(), variables)
                : null;
        String renderedBody = templateRenderer.render(template.getContent(), variables);
        RenderedMessage message = new RenderedMessage(request.recipient(), renderedSubject, renderedBody);

        NotificationEntity notification;
        try {
            notification = transactionTemplate.execute(status -> {
                NotificationEntity entity = NotificationEntity.builder()
                        .applicationId(applicationId)
                        .channel(request.channel())
                        .recipient(request.recipient())
                        .templateCode(request.template())
                        .payload(variables)
                        .status(NotificationStatus.PENDING)
                        .attempts(0)
                        .idempotencyKey(cleanIdempotencyKey)
                        .build();
                NotificationEntity saved = notificationRepository.save(entity);
                log.info("Notification {} created with status PENDING for channel {}", saved.getId(), saved.getChannel());
                return saved;
            });
        } catch (DataIntegrityViolationException ex) {
            if (cleanIdempotencyKey != null && applicationId != null) {
                Optional<NotificationEntity> existing = notificationRepository
                        .findByApplicationIdAndIdempotencyKey(applicationId, cleanIdempotencyKey);
                if (existing.isPresent()) {
                    log.info("Concurrent race handled: returning existing notification {} for key '{}'", existing.get().getId(), cleanIdempotencyKey);
                    return new SendNotificationResult(NotificationResponse.from(existing.get()), true);
                }
            }
            throw ex;
        }

        transactionTemplate.executeWithoutResult(status -> {
            NotificationEntity entity = notificationRepository.findById(notification.getId())
                    .orElseThrow(() -> new NotificationNotFoundException(notification.getId()));
            entity.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(entity);
            log.info("Notification {} transitioned to PROCESSING", notification.getId());
        });

        boolean sendSuccess = false;
        String errorMessage = null;
        try {
            sender.send(message);
            sendSuccess = true;
            log.info("Notification {} dispatched successfully via channel {}", notification.getId(), request.channel());
        } catch (Exception ex) {
            log.error("Failed to dispatch notification {} via channel {}: {}", notification.getId(), request.channel(), ex.getMessage());
            errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        }

        final boolean finalSuccess = sendSuccess;
        final String finalErrorMessage = errorMessage;
        NotificationEntity finalEntity = transactionTemplate.execute(status -> {
            NotificationEntity entity = notificationRepository.findById(notification.getId())
                    .orElseThrow(() -> new NotificationNotFoundException(notification.getId()));
            entity.setAttempts(1);
            if (finalSuccess) {
                entity.setStatus(NotificationStatus.SENT);
                entity.setSentAt(Instant.now());
                entity.setLastError(null);
            } else {
                entity.setStatus(NotificationStatus.FAILED);
                entity.setLastError(finalErrorMessage);
            }
            NotificationEntity saved = notificationRepository.save(entity);
            log.info("Notification {} completed with status {}", saved.getId(), saved.getStatus());
            return saved;
        });

        return new SendNotificationResult(NotificationResponse.from(finalEntity), false);
    }

    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(UUID id) {
        return notificationRepository.findById(id)
                .map(NotificationResponse::from)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    private void validateRecipient(NotificationChannel channel, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new InvalidNotificationException("Recipient cannot be blank");
        }
        if (channel == NotificationChannel.EMAIL && !EMAIL_PATTERN.matcher(recipient.trim()).matches()) {
            throw new InvalidNotificationException("Invalid email recipient: " + recipient);
        }
    }
}
