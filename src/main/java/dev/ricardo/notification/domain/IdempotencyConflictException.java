package dev.ricardo.notification.domain;
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() { super("Idempotency-Key was already used with different request content"); }
}
