package dev.ricardo.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AdminUserRepository extends JpaRepository<AdminUserEntity,UUID> {
    boolean existsByEmail(String email);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AdminUserEntity u where u.email=:email") Optional<AdminUserEntity> findLockedByEmail(@Param("email") String email);
    @Query(value="SELECT * FROM admin_user ORDER BY id FOR UPDATE",nativeQuery=true) List<AdminUserEntity> lockAll();
}
