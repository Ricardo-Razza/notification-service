package dev.ricardo.notification.infrastructure.persistence;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;
public interface AdminAuditRepository extends Repository<AdminAuditEntity,UUID>,JpaSpecificationExecutor<AdminAuditEntity> {
    AdminAuditEntity save(AdminAuditEntity entry);
}
