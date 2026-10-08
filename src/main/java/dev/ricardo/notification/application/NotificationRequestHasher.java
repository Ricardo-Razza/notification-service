package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

public final class NotificationRequestHasher {
    private NotificationRequestHasher() {}
    public static String hash(CreateNotificationRequest request) {
        try {
            var bytes = new ByteArrayOutputStream();
            var data = new DataOutputStream(bytes);
            write(data, request.recipient()); write(data, request.channel() == null ? null : request.channel().name());
            write(data, request.template());
            String scheduled = request.scheduledAt();
            if (scheduled != null && !scheduled.isBlank()) {
                try { scheduled = java.time.OffsetDateTime.parse(scheduled.trim()).toInstant().toString(); }
                catch (java.time.format.DateTimeParseException ignored) { }
            } else scheduled = null;
            write(data, scheduled);
            var variables = new TreeMap<>(request.safeVariables());
            data.writeInt(variables.size());
            for (var entry : variables.entrySet()) { write(data, entry.getKey()); write(data, entry.getValue()); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Request hashing failed", e); }
    }
    private static void write(DataOutputStream data, String value) throws IOException {
        if (value == null) { data.writeInt(-1); return; }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); data.writeInt(encoded.length); data.write(encoded);
    }
}
