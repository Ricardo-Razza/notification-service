package dev.ricardo.notification.application;

import dev.ricardo.notification.domain.InvalidNotificationException;
import dev.ricardo.notification.domain.InvalidTemplateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class InputLimits {
    @Value("${app.limits.max-variables:50}") private int maxVariables = 50;
    @Value("${app.limits.variable-key-length:64}") private int keyLength = 64;
    @Value("${app.limits.variable-value-length:2000}") private int valueLength = 2000;
    @Value("${app.limits.template-content-length:50000}") private int contentLength = 50000;
    public void variables(Map<String, String> variables) {
        if (variables.size() > maxVariables) throw new InvalidNotificationException("Too many template variables");
        variables.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > keyLength || value == null || value.length() > valueLength)
                throw new InvalidNotificationException("Template variable key or value exceeds configured limits");
        });
    }
    public void template(String subject, String content) {
        if (content == null || content.isBlank() || content.length() > contentLength || (subject != null && subject.length() > 255))
            throw new InvalidTemplateException("Template subject or content exceeds configured limits");
    }
}
