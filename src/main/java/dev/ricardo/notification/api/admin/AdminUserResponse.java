package dev.ricardo.notification.api.admin;
import dev.ricardo.notification.domain.AdminRole;
import dev.ricardo.notification.infrastructure.persistence.AdminUserEntity;
import java.time.Instant;
import java.util.UUID;
public record AdminUserResponse(UUID id,String email,AdminRole role,boolean active,Instant lockedUntil,Instant lastLoginAt,Instant createdAt) {
    public static AdminUserResponse from(AdminUserEntity u) {return new AdminUserResponse(u.getId(),u.getEmail(),u.getRole(),u.isActive(),u.getLockedUntil(),u.getLastLoginAt(),u.getCreatedAt());}
}
