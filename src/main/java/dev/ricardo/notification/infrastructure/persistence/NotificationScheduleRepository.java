package dev.ricardo.notification.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationScheduleRepository extends JpaRepository<NotificationScheduleEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<NotificationScheduleEntity> {
    long countByApplicationId(UUID applicationId);

    Optional<NotificationScheduleEntity> findByApplicationIdAndName(UUID applicationId, String name);

    Optional<NotificationScheduleEntity> findByIdAndApplicationId(UUID id, UUID applicationId);

    Page<NotificationScheduleEntity> findByApplicationId(UUID applicationId, Pageable pageable);

    @Query(value = "SELECT * FROM notification_schedule WHERE active = true AND next_run_at IS NOT NULL AND next_run_at <= :now ORDER BY next_run_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<NotificationScheduleEntity> findActiveForUpdateSkipLocked(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
