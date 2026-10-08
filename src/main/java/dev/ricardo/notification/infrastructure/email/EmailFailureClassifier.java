package dev.ricardo.notification.infrastructure.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.domain.UnsupportedChannelException;
import dev.ricardo.notification.template.MissingTemplateVariableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Classifica sem guardar mensagens de exceção, que podem conter dados pessoais. */
public class EmailFailureClassifier {
    public record Failure(boolean permanent, String reason) {}
    public Failure classify(Throwable error) {
        return classify(error, Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    private Failure classify(Throwable error, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) return new Failure(false, "Temporary delivery failure");
        if (error instanceof AddressException || error instanceof MailParseException || error instanceof IllegalArgumentException
                || error instanceof TemplateNotFoundException || error instanceof UnsupportedChannelException
                || error instanceof MissingTemplateVariableException) {
            return new Failure(true, "Permanent delivery failure: " + error.getClass().getSimpleName());
        }
        // Angus SMTP fornece getReturnCode; reflexão evita acoplar o contrato ao provedor Jakarta Mail.
        try {
            int code = ((Number) error.getClass().getMethod("getReturnCode").invoke(error)).intValue();
            if (code >= 400 && code < 600) return new Failure(code >= 500, "SMTP delivery rejected: " + code);
        } catch (ReflectiveOperationException ignored) { }
        if (error instanceof MailSendException mail) {
            for (Exception nested : mail.getFailedMessages().values()) {
                Failure failure = classify(nested, seen);
                if (failure.permanent() || failure.reason().startsWith("SMTP")) return failure;
            }
        }
        if (error instanceof MessagingException mail && mail.getNextException() != null) {
            Failure failure = classify(mail.getNextException(), seen);
            if (failure.permanent() || failure.reason().startsWith("SMTP")) return failure;
        }
        return classify(error.getCause(), seen);
    }
}
