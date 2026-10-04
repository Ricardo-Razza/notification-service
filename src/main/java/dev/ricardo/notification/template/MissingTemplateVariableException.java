package dev.ricardo.notification.template;

public class MissingTemplateVariableException extends RuntimeException {

    public MissingTemplateVariableException(String variable) {
        super("Variável obrigatória do template não informada: " + variable);
    }
}
