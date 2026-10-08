package dev.ricardo.notification.infrastructure.email;

import jakarta.mail.internet.AddressException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import java.net.SocketTimeoutException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class EmailFailureClassifierTest {
    private final EmailFailureClassifier classifier = new EmailFailureClassifier();
    public static class SmtpError extends Exception {
        private final int code;
        public SmtpError(int code) { super("private@example.com secret body"); this.code = code; }
        public int getReturnCode() { return code; }
    }
    @Test void permanentAddressesAndSmtp5xx() {
        assertThat(classifier.classify(new AddressException("secret address")).permanent()).isTrue();
        var result = classifier.classify(new SmtpError(550));
        assertThat(result.permanent()).isTrue(); assertThat(result.reason()).contains("550").doesNotContain("private", "secret");
    }
    @Test void transientConnectionFailuresAndSmtp4xx() {
        assertThat(classifier.classify(new SocketTimeoutException()).permanent()).isFalse();
        assertThat(classifier.classify(new SmtpError(421)).permanent()).isFalse();
    }
    @Test void traversesMailSendFailedMessages() {
        assertThat(classifier.classify(new MailSendException(Map.of(new Object(), new SmtpError(550)))).permanent()).isTrue();
    }
}
