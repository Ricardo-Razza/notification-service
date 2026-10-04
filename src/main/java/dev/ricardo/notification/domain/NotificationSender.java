package dev.ricardo.notification.domain;

/** Strategy: uma implementação por canal. */
public interface NotificationSender {

    NotificationChannel supports();

    void send(RenderedMessage message);
}
