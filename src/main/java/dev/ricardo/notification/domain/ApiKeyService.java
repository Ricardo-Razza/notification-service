package dev.ricardo.notification.domain;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class ApiKeyService {

    private static final String PREFIX = "ntf_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record GeneratedApiKey(String rawApiKey, String hash) {}

    public GeneratedApiKey generateApiKey() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawApiKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String hash = hashApiKey(rawApiKey);
        return new GeneratedApiKey(rawApiKey, hash);
    }

    public String hashApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
