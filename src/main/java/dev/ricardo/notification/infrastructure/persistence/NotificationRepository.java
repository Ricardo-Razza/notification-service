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

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationEntity n set n.status = dev.ricardo.notification.domain.NotificationStatus.PROCESSING, n.updatedAt = :now where n.id = :id and n.status = dev.ricardo.notification.domain.NotificationStatus.QUEUED")
    int claimQueued(@Param("id") UUID id, @Param("now") Instant now);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NotificationEntity n where n.id = :id")
    Optional<NotificationEntity> findLockedById(@Param("id") UUID id);

    @Query(value = "SELECT * FROM notification WHERE (status='PENDING' AND updated_at < :pending) OR (status='QUEUED' AND updated_at < :queued) OR (status='PROCESSING' AND updated_at < :processing) ORDER BY updated_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<NotificationEntity> findStalledForUpdate(@Param("pending") Instant pending, @Param("queued") Instant queued,
            @Param("processing") Instant processing, @Param("batchSize") int batchSize);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE notification SET status='QUEUED', recovery_count=recovery_count+1, updated_at=:now WHERE id=:id AND status=:status AND recovery_count=:recoveries", nativeQuery = true)
    int recover(@Param("id") String id, @Param("status") String status, @Param("recoveries") int recoveries, @Param("now") Instant now);

    @Query(value="SELECT * FROM notification WHERE status IN ('SENT','FAILED','CANCELED') AND created_at < :cutoff ORDER BY created_at,id LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery=true)
    List<NotificationEntity> findExpiredFinal(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Query(value="SELECT * FROM notification WHERE status IN ('SENT','FAILED','CANCELED') AND anonymized_at IS NULL AND created_at < :cutoff ORDER BY created_at,id LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery=true)
    List<NotificationEntity> findFinalToAnonymize(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    Optional<NotificationEntity> findByApplicationIdAndIdempotencyKey(UUID applicationId, String idempotencyKey);

    @Query(value = "SELECT * FROM notification WHERE status = 'SCHEDULED' AND scheduled_at <= :now ORDER BY scheduled_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<NotificationEntity> findScheduledForUpdateSkipLocked(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
