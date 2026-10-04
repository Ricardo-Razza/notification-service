package dev.ricardo.notification.domain;

/**
 * Mensagem já pronta para entrega: o template foi renderizado.
 * O sender só entrega; ele não conhece templates nem status.
 */
public record RenderedMessage(String recipient, String subject, String body) {
}
