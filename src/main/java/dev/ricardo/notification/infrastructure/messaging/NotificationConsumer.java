package dev.ricardo.notification.infrastructure.messaging;

import com.rabbitmq.client.Channel;
import dev.ricardo.notification.application.SenderFactory;
import dev.ricardo.notification.domain.AttemptStatus;
import dev.ricardo.notification.domain.NotificationMessage;
import dev.ricardo.notification.domain.NotificationNotFoundException;
import dev.ricardo.notification.domain.NotificationSender;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.domain.RenderedMessage;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationAttemptRepository;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import dev.ricardo.notification.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationRepository notificationRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final TemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final SenderFactory senderFactory;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Value("${app.rabbitmq.exchange:notification.exchange}")
    private String exchangeName;

    @Value("${app.rabbitmq.retry-1-routing-key:notification.retry.10s}")
    private String retry1RoutingKey;

    @Value("${app.rabbitmq.retry-2-routing-key:notification.retry.30s}")
    private String retry2RoutingKey;

    @Value("${app.rabbitmq.retry-3-routing-key:notification.retry.2m}")
    private String retry3RoutingKey;

    @Value("${app.rabbitmq.dlq-routing-key:notification.dlq}")
    private String dlqRoutingKey;

    @Value("${app.notification.max-attempts:3}")
    private int maxAttempts;

    @RabbitListener(queues = "${app.rabbitmq.queue:notification.queue}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        NotificationMessage notificationMessage;
        try {
            notificationMessage = objectMapper.readValue(message.getBody(), NotificationMessage.class);
            if (notificationMessage == null || notificationMessage.notificationId() == null) {
                throw new IllegalArgumentException("Payload missing notificationId");
            }
        } catch (Exception ex) {
            log.error("Malformed message received: routing directly to DLQ. Tag: {}", deliveryTag, ex);
            rabbitTemplate.send(exchangeName, dlqRoutingKey, message);
            channel.basicAck(deliveryTag, false);
            return;
        }

        UUID notificationId = notificationMessage.notificationId();
        try {
            processNotification(notificationId, channel, deliveryTag);
        } catch (Exception ex) {
            log.error("Unexpected error processing notification {}. Requeueing message.", notificationId, ex);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void processNotification(UUID notificationId, Channel channel, long deliveryTag) throws IOException {
        Optional<NotificationEntity> optionalEntity = notificationRepository.findById(notificationId);
        if (optionalEntity.isEmpty()) {
            log.warn("Notification {} not found in database. Discarding message.", notificationId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        NotificationEntity notification = optionalEntity.get();
        if (notification.getStatus() == NotificationStatus.SENT ||
                notification.getStatus() == NotificationStatus.CANCELED ||
                notification.getStatus() == NotificationStatus.FAILED) {
            log.info("Notification {} is already in terminal status {}. Discarding message.", notificationId, notification.getStatus());
            channel.basicAck(deliveryTag, false);
            return;
        }

        int attemptNumber = notification.getAttempts() + 1;
        int cycleAttempt = ((attemptNumber - 1) % maxAttempts) + 1;
        log.info("Processing notification {} attempt {}", notificationId, attemptNumber);

        meterRegistry.counter("notifications.attempts", "channel", notification.getChannel().name()).increment();

        // Atualiza status para PROCESSING em transação curta
        transactionTemplate.executeWithoutResult(status -> {
            notificationRepository.findById(notificationId).ifPresent(entity -> {
                entity.setStatus(NotificationStatus.PROCESSING);
                notificationRepository.save(entity);
            });
        });

        // Envio fora de transação do banco
        Instant startedAt = Instant.now();
        boolean success = false;
        String errorMessage = null;

        try {
            TemplateEntity template = templateRepository.findByCodeAndChannel(
                    notification.getTemplateCode(),
                    notification.getChannel()
            ).orElseThrow(() -> new TemplateNotFoundException(
                    String.format("Template '%s' not found for channel '%s'", notification.getTemplateCode(), notification.getChannel())
            ));

            String renderedSubject = (template.getSubject() != null)
                    ? templateRenderer.render(template.getSubject(), notification.getPayload())
                    : null;
            String renderedContent = templateRenderer.render(template.getContent(), notification.getPayload());

            NotificationSender sender = senderFactory.get(notification.getChannel());
            sender.send(new RenderedMessage(
                    notification.getRecipient(),
                    renderedSubject,
                    renderedContent
            ));
            success = true;
        } catch (Exception ex) {
            success = false;
            errorMessage = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("Notification {} failed on attempt {}: {}", notificationId, attemptNumber, errorMessage);
        }

        Instant finishedAt = Instant.now();
        meterRegistry.timer("notifications.send.duration", "channel", notification.getChannel().name())
                .record(java.time.Duration.between(startedAt, finishedAt));

        if (success) {
            meterRegistry.counter("notifications.sent", "channel", notification.getChannel().name()).increment();
            final Instant finalFinishedAt = finishedAt;
            transactionTemplate.executeWithoutResult(status -> {
                NotificationEntity entity = notificationRepository.findById(notificationId)
                        .orElseThrow(() -> new NotificationNotFoundException(notificationId));
                entity.setStatus(NotificationStatus.SENT);
                entity.setSentAt(finalFinishedAt);
                entity.setAttempts(attemptNumber);
                entity.setLastError(null);
                notificationRepository.save(entity);

                NotificationAttemptEntity attempt = NotificationAttemptEntity.builder()
                        .id(UUID.randomUUID())
                        .notificationId(notificationId)
                        .attemptNumber(attemptNumber)
                        .status(AttemptStatus.SUCCESS)
                        .errorMessage(null)
                        .startedAt(startedAt)
                        .finishedAt(finalFinishedAt)
                        .build();
                attemptRepository.save(attempt);
            });

            log.info("Notification {} sent successfully on attempt {}", notificationId, attemptNumber);
            channel.basicAck(deliveryTag, false);
        } else {
            meterRegistry.counter("notifications.failed", "channel", notification.getChannel().name()).increment();
            final String finalErrorMessage = errorMessage;
            final Instant finalFinishedAt = finishedAt;
            boolean shouldRetry = cycleAttempt < maxAttempts;

            transactionTemplate.executeWithoutResult(status -> {
                NotificationEntity entity = notificationRepository.findById(notificationId)
                        .orElseThrow(() -> new NotificationNotFoundException(notificationId));
                entity.setAttempts(attemptNumber);
                entity.setLastError(finalErrorMessage);
                if (shouldRetry) {
                    entity.setStatus(NotificationStatus.QUEUED);
                } else {
                    entity.setStatus(NotificationStatus.FAILED);
                }
                notificationRepository.save(entity);

                NotificationAttemptEntity attempt = NotificationAttemptEntity.builder()
                        .id(UUID.randomUUID())
                        .notificationId(notificationId)
                        .attemptNumber(attemptNumber)
                        .status(AttemptStatus.FAILURE)
                        .errorMessage(finalErrorMessage)
                        .startedAt(startedAt)
                        .finishedAt(finalFinishedAt)
                        .build();
                attemptRepository.save(attempt);
            });

            if (shouldRetry) {
                String retryRoutingKey = selectRetryRoutingKey(cycleAttempt);
                log.info("Notification {} scheduled for retry (attempt {}) to routing key {}", notificationId, attemptNumber, retryRoutingKey);
                rabbitTemplate.convertAndSend(exchangeName, retryRoutingKey, new NotificationMessage(notificationId));
            } else {
                log.error("Notification {} exhausted all {} attempts, moving to DLQ", notificationId, maxAttempts);
                rabbitTemplate.convertAndSend(exchangeName, dlqRoutingKey, new NotificationMessage(notificationId));
            }

            channel.basicAck(deliveryTag, false);
        }
    }

    private String selectRetryRoutingKey(int attemptNumber) {
        if (attemptNumber == 1) {
            return retry1RoutingKey;
        } else if (attemptNumber == 2) {
            return retry2RoutingKey;
        } else {
            return retry3RoutingKey;
        }
    }
}
