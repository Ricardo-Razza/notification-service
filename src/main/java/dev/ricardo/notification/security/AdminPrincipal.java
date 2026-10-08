package dev.ricardo.notification.security;
import dev.ricardo.notification.domain.AdminRole;
import java.util.UUID;
public record AdminPrincipal(UUID id,String email,AdminRole role) implements java.io.Serializable {}
