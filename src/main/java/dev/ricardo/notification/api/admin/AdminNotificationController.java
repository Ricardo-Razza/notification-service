package dev.ricardo.notification.api.admin;
import dev.ricardo.notification.application.*;
import dev.ricardo.notification.api.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import dev.ricardo.notification.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/admin/notifications") @RequiredArgsConstructor
public class AdminNotificationController {
    private final NotificationService notifications;
    private final ApplicationRepository applications;
    private final AdminAuditService audit;
    @io.swagger.v3.oas.annotations.media.Schema(name="CreateAdminNotification")
    public record Create(@NotNull UUID applicationId,@NotBlank @Size(max=320) String recipient,@NotNull NotificationChannel channel,
            @NotBlank @Size(max=100) String template,Map<String,String> variables,String scheduledAt,@Size(max=255) String idempotencyKey) {}
    @PostMapping public ResponseEntity<NotificationResponse> send(@Valid @RequestBody Create request,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {
        var application=applications.findById(request.applicationId()).orElseThrow(()->new ApplicationNotFoundException(request.applicationId()));
        if(!application.isActive())throw new InvalidNotificationException("Selected application is inactive");
        if(key!=null&&request.idempotencyKey()!=null&&!key.equals(request.idempotencyKey()))throw new InvalidNotificationException("Header and body Idempotency-Key must match");
        try(var origin=MDC.putCloseable("origin","PANEL");var app=MDC.putCloseable("applicationId",request.applicationId().toString())) {
            var result=notifications.sendNotification(new CreateNotificationRequest(request.recipient(),request.channel(),request.template(),request.variables(),request.scheduledAt()),request.applicationId(),key==null?request.idempotencyKey():key);
            audit.record(user.id(),result.replayed()?"NOTIFICATION_REPLAYED":"NOTIFICATION_CREATED","NOTIFICATION",result.response().id(),Map.of("origin","PANEL"),http.getRemoteAddr());
            return result.replayed()?ResponseEntity.ok().header("Idempotent-Replayed","true").body(result.response()):ResponseEntity.accepted().body(result.response());
        }
    }
    @PostMapping("/{id}/retry") public NotificationResponse retry(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {
        var response=notifications.retryNotification(id,null);audit.record(user.id(),"NOTIFICATION_RETRIED","NOTIFICATION",id,Map.of(),http.getRemoteAddr());return response;
    }
    @DeleteMapping("/{id}") public NotificationResponse cancel(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {
        var response=notifications.cancelScheduledNotification(id,null);audit.record(user.id(),"NOTIFICATION_CANCELED","NOTIFICATION",id,Map.of(),http.getRemoteAddr());return response;
    }
}
