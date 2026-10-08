package dev.ricardo.notification.application;

import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.api.admin.AdminUserResponse;
import dev.ricardo.notification.infrastructure.persistence.*;
import dev.ricardo.notification.security.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminUserService {
    private final AdminUserRepository users;
    private final AdminAuditService audit;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    @Value("${app.admin.lock-minutes:15}") private long lockMinutes=15;
    private String dummyHash;
    @jakarta.annotation.PostConstruct void initializeDummy() {dummyHash=encoder.encode(UUID.randomUUID().toString());}
    private record Outcome(AdminPrincipal principal,int status) {}
    public AdminPrincipal login(String email,String password,String ip) {
        Outcome result=transactions.execute(tx -> {
            var user=users.findLockedByEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
            Instant now=Instant.now();
            if(user!=null && user.getLockedUntil()!=null && user.getLockedUntil().isAfter(now)) {
                audit.record(user.getId(),"LOGIN_FAILED","USER",user.getId(),Map.of("reason","locked"),ip); return new Outcome(null,423);
            }
            if(user!=null && user.getLockedUntil()!=null) {user.setLockedUntil(null);user.setFailedAttempts(0);}
            boolean valid=password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72&&encoder.matches(password,user==null?dummyHash:user.getPasswordHash());
            if(user==null || !user.isActive() || !valid) {
                if(user!=null) {
                    user.setFailedAttempts(user.getFailedAttempts()+1);
                    if(user.getFailedAttempts()>=5) user.setLockedUntil(now.plus(Duration.ofMinutes(lockMinutes)));
                    users.save(user);
                }
                audit.record(user==null?null:user.getId(),"LOGIN_FAILED","USER",user==null?null:user.getId(),Map.of("reason","invalid_credentials"),ip);
                return new Outcome(null,user!=null&&user.getLockedUntil()!=null?423:401);
            }
            user.setFailedAttempts(0);user.setLockedUntil(null);user.setLastLoginAt(now);users.save(user);
            audit.record(user.getId(),"LOGIN","USER",user.getId(),Map.of(),ip);
            return new Outcome(new AdminPrincipal(user.getId(),user.getEmail(),user.getRole()),200);
        });
        if(result.principal()==null) throw new AdminAuthenticationException(result.status(),result.status()==423?"Account is temporarily locked":"Invalid credentials");
        return result.principal();
    }
    @Transactional
    public AdminUserResponse create(UUID actor,String email,String password,AdminRole role,String ip) {
        validatePassword(password); String normalized=email.trim().toLowerCase(Locale.ROOT);
        if(role==null || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || normalized.length()>320)
            throw new InvalidNotificationException("Invalid admin user fields");
        if(users.existsByEmail(normalized)) throw new dev.ricardo.notification.domain.ScheduleConflictException("Admin email already exists");
        var user=users.save(AdminUserEntity.builder().email(normalized).passwordHash(encoder.encode(password)).role(role).active(true).build());
        audit.record(actor,"USER_CREATED","USER",user.getId(),Map.of("role",role.name()),ip); return AdminUserResponse.from(user);
    }
    @Transactional
    public AdminUserResponse update(UUID actor,UUID id,AdminRole role,Boolean active,String password,String ip) {
        var all=users.lockAll(); var user=all.stream().filter(u->u.getId().equals(id)).findFirst().orElseThrow(()->new ApplicationNotFoundException(id));
        long admins=all.stream().filter(u->u.isActive()&&u.getRole()==AdminRole.ADMIN).count();
        if(user.isActive()&&user.getRole()==AdminRole.ADMIN&&admins<=1&&(Boolean.FALSE.equals(active)||role==AdminRole.VIEWER))
            throw new ScheduleConflictException("The last active ADMIN cannot be disabled or demoted");
        if(role!=null) user.setRole(role); if(active!=null) user.setActive(active);
        if(password!=null) {validatePassword(password);user.setPasswordHash(encoder.encode(password));user.setFailedAttempts(0);user.setLockedUntil(null);}
        users.save(user); audit.record(actor,"USER_UPDATED","USER",id,Map.of("role",user.getRole().name(),"active",Boolean.toString(user.isActive())),ip);
        return AdminUserResponse.from(user);
    }
    @Transactional(readOnly=true)
    public List<AdminUserResponse> list() {return users.findAll(org.springframework.data.domain.PageRequest.of(0,100)).map(AdminUserResponse::from).getContent();}
    private void validatePassword(String password) {
        if(password==null || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new InvalidNotificationException("Password must have at least 12 characters and at most 72 UTF-8 bytes");
    }
}
