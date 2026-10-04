package dev.ricardo.notification.application;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationSender;
import dev.ricardo.notification.domain.UnsupportedChannelException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory: o Spring injeta todos os NotificationSender e aqui eles viram um mapa por canal.
 * Para criar um canal novo basta criar outro @Component que implemente NotificationSender.
 */
@Component
public class SenderFactory {

    private final Map<NotificationChannel, NotificationSender> senders;

    public SenderFactory(List<NotificationSender> senders) {
        // Se dois senders declararem o mesmo canal, o toMap lança exceção na subida.
        this.senders = senders.stream()
                .collect(Collectors.toMap(NotificationSender::supports, Function.identity()));
    }

    public NotificationSender get(NotificationChannel channel) {
        NotificationSender sender = senders.get(channel);
        if (sender == null) {
            throw new UnsupportedChannelException(channel);
        }
        return sender;
    }
}
