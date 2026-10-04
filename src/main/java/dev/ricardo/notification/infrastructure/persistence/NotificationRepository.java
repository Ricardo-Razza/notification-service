package dev.ricardo.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID>, JpaSpecificationExecutor<NotificationEntity> {

    Optional<NotificationEntity> findByApplicationIdAndIdempotencyKey(UUID applicationId, String idempotencyKey);

    @Query(value = "SELECT * FROM notification WHERE status = 'SCHEDULED' AND scheduled_at <= :now ORDER BY scheduled_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<NotificationEntity> findScheduledForUpdateSkipLocked(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
