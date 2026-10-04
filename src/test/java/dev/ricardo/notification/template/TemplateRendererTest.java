package dev.ricardo.notification.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void replacesVariables() {
        String result = renderer.render("Olá {{name}}, seu código é {{ code }}.",
                Map.of("name", "Ricardo", "code", "123456"));
        assertEquals("Olá Ricardo, seu código é 123456.", result);
    }

    @Test
    void keepsTextWithoutVariables() {
        assertEquals("Sem variáveis", renderer.render("Sem variáveis", Map.of()));
    }

    @Test
    void acceptsSpecialCharactersInValues() {
        assertEquals("Valor: $1 \\ fim", renderer.render("Valor: {{v}} fim", Map.of("v", "$1 \\")));
    }

    @Test
    void failsWhenVariableIsMissing() {
        assertThrows(MissingTemplateVariableException.class,
                () -> renderer.render("Olá {{name}}", Map.of()));
    }
}
