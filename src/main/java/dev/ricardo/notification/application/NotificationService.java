package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.api.NotificationResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
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

        NotificationEntity notification = transactionTemplate.execute(status -> {
            NotificationEntity entity = NotificationEntity.builder()
                    .channel(request.channel())
                    .recipient(request.recipient())
                    .templateCode(request.template())
                    .payload(variables)
                    .status(NotificationStatus.PENDING)
                    .attempts(0)
                    .build();
            return notificationRepository.save(entity);
        });

        transactionTemplate.executeWithoutResult(status -> {
            NotificationEntity entity = notificationRepository.findById(notification.getId())
                    .orElseThrow(() -> new NotificationNotFoundException(notification.getId()));
            entity.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(entity);
        });

        boolean sendSuccess = false;
        String errorMessage = null;
        try {
            sender.send(message);
            sendSuccess = true;
            log.info("Notification {} sent successfully to recipient", notification.getId());
        } catch (Exception ex) {
            log.error("Failed to send notification {}", notification.getId(), ex);
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
            return notificationRepository.save(entity);
        });

        return NotificationResponse.from(finalEntity);
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
