package dev.ricardo.notification.infrastructure.email;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationSender;
import dev.ricardo.notification.domain.RenderedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailNotificationSender(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public NotificationChannel supports() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(RenderedMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.recipient());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
    }
}
