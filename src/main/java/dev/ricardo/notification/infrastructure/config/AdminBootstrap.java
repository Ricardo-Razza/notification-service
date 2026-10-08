package dev.ricardo.notification.infrastructure.config;
import dev.ricardo.notification.application.AdminUserService;
import dev.ricardo.notification.domain.AdminRole;
import dev.ricardo.notification.infrastructure.persistence.AdminUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component @RequiredArgsConstructor @Slf4j
public class AdminBootstrap implements ApplicationRunner {
    private final AdminUserRepository users;
    private final AdminUserService service;
    @Value("${app.admin.email:}") private String email;
    @Value("${app.admin.password:}") private String password;
    @Override public void run(ApplicationArguments args) {
        if(users.count()!=0) return;
        if(email.isBlank()||password.isBlank()) {log.warn("Admin bootstrap skipped: ADMIN_EMAIL and ADMIN_PASSWORD are required on first startup");return;}
        service.create(null,email,password,AdminRole.ADMIN,"bootstrap");
    }
}
