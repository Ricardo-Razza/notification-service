package dev.ricardo.notification.domain;
public class RateLimitException extends RuntimeException {
    private final long retryAfter;
    public RateLimitException(long retryAfter) { super("Notification rate limit exceeded"); this.retryAfter = retryAfter; }
    public long retryAfter() { return retryAfter; }
}
