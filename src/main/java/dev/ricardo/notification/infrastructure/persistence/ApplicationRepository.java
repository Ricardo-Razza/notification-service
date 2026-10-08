package dev.ricardo.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from ApplicationEntity a where a.id = :id")
    Optional<ApplicationEntity> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<ApplicationEntity> findByApiKeyHash(String apiKeyHash);
    Optional<ApplicationEntity> findByApiKeyHashPreviousAndPreviousKeyExpiresAtAfter(String apiKeyHashPrevious, java.time.Instant now);

    Optional<ApplicationEntity> findByName(String name);

    boolean existsByName(String name);
}
