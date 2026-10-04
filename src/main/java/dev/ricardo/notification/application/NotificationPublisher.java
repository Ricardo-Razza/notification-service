package dev.ricardo.notification.application;

import java.util.UUID;

public interface NotificationPublisher {

    void publish(UUID notificationId);
}
