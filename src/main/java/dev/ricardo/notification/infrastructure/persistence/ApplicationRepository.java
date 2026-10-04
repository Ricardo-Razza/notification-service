package dev.ricardo.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {

    Optional<ApplicationEntity> findByApiKeyHash(String apiKeyHash);

    Optional<ApplicationEntity> findByName(String name);

    boolean existsByName(String name);
}
