package dev.ricardo.notification.template;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Troca {{variavel}} pelos valores informados. */
@Component
public class TemplateRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    public String render(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if(value==null&&"today".equals(name))value=java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
            if(value==null&&"now".equals(name))value=java.time.Instant.now().toString();
            if (value == null) {
                throw new MissingTemplateVariableException(name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
