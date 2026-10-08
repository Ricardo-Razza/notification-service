package dev.ricardo.notification.infrastructure.persistence;

import dev.ricardo.notification.domain.AdminRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="admin_user") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AdminUserEntity {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @Column(nullable=false,length=320,unique=true) private String email;
    @Column(name="password_hash",nullable=false,length=100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private AdminRole role;
    @Builder.Default @Column(nullable=false) private boolean active=true;
    @Column(name="failed_attempts",nullable=false) private int failedAttempts;
    @Column(name="locked_until") private Instant lockedUntil;
    @Column(name="last_login_at") private Instant lastLoginAt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void insert() { if(id==null) id=UUID.randomUUID(); createdAt=Instant.now(); updatedAt=createdAt; }
    @PreUpdate void update() {updatedAt=Instant.now();}
}
