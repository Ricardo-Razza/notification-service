package dev.ricardo.notification.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="admin_audit_log") @Immutable @Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class AdminAuditEntity {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="admin_user_id",length=36) private UUID adminUserId;
    @Column(nullable=false,length=60) private String action;
    @Column(name="target_type",nullable=false,length=40) private String targetType;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="target_id",length=36) private UUID targetId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="json") private Map<String,String> details;
    @Column(nullable=false,length=45) private String ip;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
}
