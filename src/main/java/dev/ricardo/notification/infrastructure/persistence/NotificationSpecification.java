package dev.ricardo.notification.infrastructure.persistence;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NotificationSpecification {

    private NotificationSpecification() {
    }

    public static Specification<NotificationEntity> withFilters(
            UUID applicationId,
            NotificationStatus status,
            NotificationChannel channel,
            String recipient,
            Instant createdFrom,
            Instant createdTo
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (applicationId != null) {
                predicates.add(cb.equal(root.get("applicationId"), applicationId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (channel != null) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (recipient != null && !recipient.isBlank()) {
                predicates.add(cb.equal(root.get("recipient"), recipient.trim()));
            }
            if (createdFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
