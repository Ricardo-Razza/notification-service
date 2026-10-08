package dev.ricardo.notification.infrastructure.persistence;

import dev.ricardo.notification.domain.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<TemplateEntity> {

    Optional<TemplateEntity> findByApplicationIdAndCodeAndChannel(UUID applicationId, String code, NotificationChannel channel);

    default Optional<TemplateEntity> findByCodeAndChannel(String code, NotificationChannel channel) {
        return findByApplicationIdAndCodeAndChannel(null, code, channel);
    }

    default Optional<TemplateEntity> findVisible(UUID applicationId, String code, NotificationChannel channel) {
        if (applicationId != null) {
            var owned = findByApplicationIdAndCodeAndChannel(applicationId, code, channel);
            if (owned.isPresent()) return owned;
        }
        return findByCodeAndChannel(code, channel);
    }

    boolean existsByApplicationIdAndCodeAndChannel(UUID applicationId, String code, NotificationChannel channel);

    default boolean existsByCodeAndChannel(String code, NotificationChannel channel) {
        return existsByApplicationIdAndCodeAndChannel(null, code, channel);
    }

    @org.springframework.data.jpa.repository.Query("select t from TemplateEntity t where (t.applicationId = :applicationId or t.applicationId is null) and (:channel is null or t.channel = :channel)")
    java.util.List<TemplateEntity> findVisibleTemplates(@org.springframework.data.repository.query.Param("applicationId") UUID applicationId,
            @org.springframework.data.repository.query.Param("channel") NotificationChannel channel);

    java.util.List<TemplateEntity> findByChannel(NotificationChannel channel);
}
