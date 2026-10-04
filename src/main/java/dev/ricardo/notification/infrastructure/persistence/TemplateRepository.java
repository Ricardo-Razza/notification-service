package dev.ricardo.notification.infrastructure.persistence;

import dev.ricardo.notification.domain.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, UUID> {

    Optional<TemplateEntity> findByCodeAndChannel(String code, NotificationChannel channel);

    boolean existsByCodeAndChannel(String code, NotificationChannel channel);

    java.util.List<TemplateEntity> findByChannel(NotificationChannel channel);
}
