package dev.ricardo.notification.domain;

public class UnsupportedChannelException extends RuntimeException {

    public UnsupportedChannelException(NotificationChannel channel) {
        super("Nenhum sender configurado para o canal " + channel);
    }
}
