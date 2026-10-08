package dev.ricardo.notification.infrastructure.messaging;

import dev.ricardo.notification.application.NotificationPublisher;
import dev.ricardo.notification.domain.NotificationMessage;
import dev.ricardo.notification.domain.NotificationPublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitNotificationPublisher implements NotificationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final dev.ricardo.notification.infrastructure.persistence.NotificationRepository notificationRepository;

    @Value("${app.rabbitmq.exchange:notification.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key:notification.send}")
    private String routingKey;

    @Override
    public void publish(UUID notificationId) {
        var previous=org.slf4j.MDC.getCopyOfContextMap();
        org.slf4j.MDC.put("notificationId",notificationId.toString());
        try {
            notificationRepository.findById(notificationId).ifPresent(n->{
                if(n.getApplicationId()!=null)org.slf4j.MDC.put("applicationId",n.getApplicationId().toString());
                if(n.getRequestId()!=null)org.slf4j.MDC.put("requestId",n.getRequestId());
                org.slf4j.MDC.put("origin",n.getOrigin());
            });
            publishMessage(notificationId);
        }finally{org.slf4j.MDC.clear();if(previous!=null)org.slf4j.MDC.setContextMap(previous);}
    }

    private void publishMessage(UUID notificationId) {
        log.info("Publishing notification {} to exchange '{}' with routing key '{}'", notificationId, exchange, routingKey);
        NotificationMessage message = new NotificationMessage(notificationId, org.slf4j.MDC.get("requestId"));
        CorrelationData correlationData = new CorrelationData(notificationId.toString());

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, m -> {
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return m;
            }, correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm == null || !confirm.ack()) {
                String reason = confirm != null ? confirm.reason() : "Timeout waiting for confirmation";
                log.error("Failed to publish notification {}: broker NACK or timeout. Reason: {}", notificationId, reason);
                throw new NotificationPublishException("Broker did not acknowledge notification " + notificationId + ": " + reason);
            }
            if (correlationData.getReturned() != null) {
                throw new NotificationPublishException("Notification message was not routed to a queue");
            }
            log.info("Notification {} confirmed published to RabbitMQ", notificationId);
        } catch (NotificationPublishException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error publishing notification {} to RabbitMQ: {}", notificationId, ex.getClass().getSimpleName());
            throw new NotificationPublishException("Failed to publish notification to queue", ex);
        }
    }
}
