package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.api.NotificationResponse;
import dev.ricardo.notification.api.PageResponse;
import dev.ricardo.notification.api.SendNotificationResult;
import dev.ricardo.notification.domain.InvalidNotificationException;
import dev.ricardo.notification.domain.NotificationCancelConflictException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationNotFoundException;
import dev.ricardo.notification.domain.NotificationPublishException;
import dev.ricardo.notification.domain.NotificationRetryConflictException;
import dev.ricardo.notification.domain.NotificationSender;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.domain.RenderedMessage;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.api.NotificationAttemptResponse;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationSpecification;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import dev.ricardo.notification.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
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
    private final NotificationAttemptRepository attemptRepository;
    private final TemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final SenderFactory senderFactory;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;
    private final InputLimits inputLimits;
    private final NotificationRateLimiter rateLimiter;

    @org.springframework.beans.factory.annotation.Value("${app.scheduling.max-future-days:365}")
    private int maxFutureDays = 365;

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
        return sendNotification(request, applicationId, idempotencyKey, true);
    }

    public SendNotificationResult sendNotification(CreateNotificationRequest request, UUID applicationId, String idempotencyKey, boolean countLimit) {
        inputLimits.variables(request.safeVariables());
        if (countLimit) rateLimiter.reserve(applicationId, 1);
        String requestHash = NotificationRequestHasher.hash(request);
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
                checkRequestHash(existing.get(), requestHash);
                log.info("Idempotent request replayed for notification {}", existing.get().getId());
                return new SendNotificationResult(NotificationResponse.from(existing.get()), true);
            }
        }

        validateRecipient(request.channel(), request.recipient());

        senderFactory.get(request.channel());

        TemplateEntity template = (applicationId == null
                ? templateRepository.findByCodeAndChannel(request.template(), request.channel())
                : templateRepository.findVisible(applicationId, request.template(), request.channel()))
                .orElseThrow(() -> new TemplateNotFoundException(
                        String.format("Template '%s' not found for channel '%s'", request.template(), request.channel())
                ));

        Map<String, String> variables = request.safeVariables();
        // Validar variáveis do template antes de enfileirar
        if (template.getSubject() != null) {
            templateRenderer.render(template.getSubject(), variables);
        }
        templateRenderer.render(template.getContent(), variables);

        Instant scheduledInstant = null;
        if (request.scheduledAt() != null && !request.scheduledAt().isBlank()) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(request.scheduledAt().trim());
                scheduledInstant = odt.toInstant();
            } catch (DateTimeParseException ex) {
                throw new InvalidNotificationException("Invalid scheduledAt format. Must be ISO-8601 with offset (e.g. 2026-10-05T10:00:00-03:00)");
            }

            Instant now = Instant.now();
            if (!scheduledInstant.isAfter(now)) {
                throw new InvalidNotificationException("scheduledAt must be in the future");
            }

            Instant maxFuture = now.plus(java.time.Duration.ofDays(maxFutureDays));
            if (scheduledInstant.isAfter(maxFuture)) {
                throw new InvalidNotificationException(String.format("scheduledAt cannot exceed maximum future scheduling limit of %d days", maxFutureDays));
            }
        }

        final Instant finalScheduledInstant = scheduledInstant;
        NotificationEntity notification;
        try {
            notification = transactionTemplate.execute(status -> {
                NotificationEntity entity = NotificationEntity.builder()
                        .applicationId(applicationId)
                        .channel(request.channel())
                        .recipient(request.recipient())
                        .templateCode(request.template())
                        .payload(variables)
                        .status(finalScheduledInstant != null ? NotificationStatus.SCHEDULED : NotificationStatus.PENDING)
                        .scheduledAt(finalScheduledInstant)
                        .attempts(0)
                        .idempotencyKey(cleanIdempotencyKey)
                        .requestHash(requestHash)
                        .requestId(org.slf4j.MDC.get("requestId"))
                        .origin("PANEL".equals(org.slf4j.MDC.get("origin"))?"PANEL":"APPLICATION")
                        .build();
                NotificationEntity saved = notificationRepository.save(entity);
                log.info("Notification {} created with status {} for channel {}", saved.getId(), saved.getStatus(), saved.getChannel());
                return saved;
            });
        } catch (DataIntegrityViolationException ex) {
            if (cleanIdempotencyKey != null && applicationId != null) {
                Optional<NotificationEntity> existing = notificationRepository
                        .findByApplicationIdAndIdempotencyKey(applicationId, cleanIdempotencyKey);
                if (existing.isPresent()) {
                    checkRequestHash(existing.get(), requestHash);
                    log.info("Concurrent race handled: returning existing notification {}", existing.get().getId());
                    return new SendNotificationResult(NotificationResponse.from(existing.get()), true);
                }
            }
            throw ex;
        }

        // Se for agendado para o futuro, NÃO publica na fila e responde 202
        if (finalScheduledInstant != null) {
            log.info("Notification {} is SCHEDULED for {}. Skipping queue publishing.", notification.getId(), finalScheduledInstant);
            return new SendNotificationResult(NotificationResponse.from(notification), false);
        }

        // QUEUED deve estar commitado antes da publicação, para o worker poder reivindicar a mensagem.
        NotificationEntity queuedEntity = transactionTemplate.execute(status -> {
            NotificationEntity entity = notificationRepository.findById(notification.getId())
                    .orElseThrow(() -> new NotificationNotFoundException(notification.getId()));
            entity.setStatus(NotificationStatus.QUEUED);
            return notificationRepository.save(entity);
        });

        // Publica fora da transação do banco (sem I/O dentro de transação)
        try {
            notificationPublisher.publish(notification.getId());
        } catch (Exception ex) {
            transactionTemplate.executeWithoutResult(status -> {
                notificationRepository.findLockedById(notification.getId()).ifPresent(entity -> {
                    if (entity.getStatus() == NotificationStatus.QUEUED) {
                        entity.setStatus(NotificationStatus.PENDING);
                        notificationRepository.save(entity);
                    }
                });
            });
            log.error("Failed to publish notification {} to messaging queue. Type={}", notification.getId(), ex.getClass().getSimpleName());
            throw (ex instanceof NotificationPublishException npe)
                    ? npe
                    : new NotificationPublishException("Failed to publish notification to queue", ex);
        }

        return new SendNotificationResult(NotificationResponse.from(queuedEntity), false);
    }

    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(UUID id) {
        return getNotificationById(id, null);
    }

    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(UUID id, UUID applicationId) {
        NotificationEntity entity = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
        if (applicationId != null && !applicationId.equals(entity.getApplicationId())) {
            throw new NotificationNotFoundException(id);
        }
        var attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
        var attemptResponses = attempts.stream()
                .map(NotificationAttemptResponse::from)
                .toList();
        return NotificationResponse.from(entity, attemptResponses);
    }

    public NotificationResponse retryNotification(UUID id, UUID applicationId) {
        NotificationEntity notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));

        if (applicationId != null && !applicationId.equals(notification.getApplicationId())) {
            throw new NotificationNotFoundException(id);
        }

        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new NotificationRetryConflictException(
                    String.format("Notification %s cannot be retried because it is in status %s (only FAILED notifications can be retried)", id, notification.getStatus())
            );
        }

        NotificationEntity queuedEntity = transactionTemplate.execute(status -> {
            NotificationEntity entity = notificationRepository.findLockedById(id)
                    .orElseThrow(() -> new NotificationNotFoundException(id));
            if (entity.getStatus() != NotificationStatus.FAILED) {
                throw new NotificationRetryConflictException("Only FAILED notifications can be retried");
            }
            entity.setStatus(NotificationStatus.QUEUED);
            return notificationRepository.save(entity);
        });

        try {
            notificationPublisher.publish(id);
        } catch (Exception ex) {
            log.error("Failed to republish notification {} to messaging queue. Type={}", id, ex.getClass().getSimpleName());
            throw (ex instanceof NotificationPublishException npe)
                    ? npe
                    : new NotificationPublishException("Failed to republish notification to queue", ex);
        }

        var attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
        var attemptResponses = attempts.stream()
                .map(NotificationAttemptResponse::from)
                .toList();
        return NotificationResponse.from(queuedEntity, attemptResponses);
    }

    public NotificationResponse cancelScheduledNotification(UUID id, UUID applicationId) {
        NotificationEntity notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));

        if (applicationId != null && !applicationId.equals(notification.getApplicationId())) {
            throw new NotificationNotFoundException(id);
        }

        if (notification.getStatus() != NotificationStatus.SCHEDULED) {
            throw new NotificationCancelConflictException(
                    String.format("Notification %s cannot be canceled because it is in status %s (only SCHEDULED notifications can be canceled)", id, notification.getStatus())
            );
        }

        NotificationEntity canceledEntity = transactionTemplate.execute(status -> {
            NotificationEntity entity = notificationRepository.findLockedById(id)
                    .orElseThrow(() -> new NotificationNotFoundException(id));
            if (entity.getStatus() != NotificationStatus.SCHEDULED) {
                throw new NotificationCancelConflictException("Only SCHEDULED notifications can be canceled");
            }
            entity.setStatus(NotificationStatus.CANCELED);
            return notificationRepository.save(entity);
        });

        var attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
        var attemptResponses = attempts.stream()
                .map(NotificationAttemptResponse::from)
                .toList();
        return NotificationResponse.from(canceledEntity, attemptResponses);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getNotifications(
            UUID applicationId,
            String status,
            String channel,
            String recipient,
            String createdFrom,
            String createdTo,
            Integer page,
            Integer size
    ) {
        int pageIndex = (page != null) ? page : 0;
        int pageSize = (size != null) ? size : 20;

        if (pageIndex < 0) {
            throw new InvalidNotificationException("Page index must be 0 or greater");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new InvalidNotificationException("Page size must be between 1 and 100");
        }

        NotificationStatus parsedStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                parsedStatus = NotificationStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidNotificationException("Invalid status: '" + status + "'. Allowed values: " + Arrays.toString(NotificationStatus.values()));
            }
        }

        NotificationChannel parsedChannel = null;
        if (channel != null && !channel.isBlank()) {
            try {
                parsedChannel = NotificationChannel.valueOf(channel.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidNotificationException("Invalid channel: '" + channel + "'. Allowed values: " + Arrays.toString(NotificationChannel.values()));
            }
        }

        String parsedRecipient = (recipient != null && !recipient.isBlank()) ? recipient.trim() : null;
        Instant from = parseIsoDate(createdFrom, "createdFrom");
        Instant to = parseIsoDate(createdTo, "createdTo");

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidNotificationException("createdFrom cannot be after createdTo");
        }

        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<NotificationEntity> spec = NotificationSpecification.withFilters(
                applicationId,
                parsedStatus,
                parsedChannel,
                parsedRecipient,
                from,
                to
        );

        Page<NotificationResponse> result = notificationRepository.findAll(spec, pageable)
                .map(NotificationResponse::from);

        return PageResponse.of(result);
    }

    private Instant parseIsoDate(String dateStr, String fieldName) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(dateStr.trim());
        } catch (Exception e1) {
            try {
                return OffsetDateTime.parse(dateStr.trim()).toInstant();
            } catch (Exception e2) {
                try {
                    return LocalDateTime.parse(dateStr.trim()).toInstant(ZoneOffset.UTC);
                } catch (Exception e3) {
                    throw new InvalidNotificationException(
                            String.format("Invalid ISO-8601 format for %s: '%s'", fieldName, dateStr)
                    );
                }
            }
        }
    }

    private void checkRequestHash(NotificationEntity existing, String requestHash) {
        if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
            throw new dev.ricardo.notification.domain.IdempotencyConflictException();
        }
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
