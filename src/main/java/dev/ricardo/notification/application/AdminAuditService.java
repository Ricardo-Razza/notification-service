package dev.ricardo.notification.application;
import dev.ricardo.notification.infrastructure.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminAuditService {
    private final AdminAuditRepository audit;
    @Transactional
    public void record(UUID user,String action,String targetType,UUID targetId,Map<String,String> details,String ip) {
        audit.save(AdminAuditEntity.builder().id(UUID.randomUUID()).adminUserId(user).action(action).targetType(targetType)
                .targetId(targetId).details(details).ip(ip==null?"unknown":ip).createdAt(Instant.now()).build());
    }
}
