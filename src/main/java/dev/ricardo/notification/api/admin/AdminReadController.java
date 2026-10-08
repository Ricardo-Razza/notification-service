package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.AdminQueryService;
import dev.ricardo.notification.api.PageResponse;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.security.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name="Admin reporting")
public class AdminReadController {
    private final AdminQueryService queries;
    @GetMapping("/notifications") public PageResponse<AdminNotificationResponse> notifications(
            @RequestParam(required=false) UUID applicationId,@RequestParam(required=false) List<NotificationStatus> status,
            @RequestParam(required=false) NotificationChannel channel,@RequestParam(required=false) String recipient,
            @RequestParam(required=false) Instant createdFrom,@RequestParam(required=false) Instant createdTo,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="createdAt,desc") String sort,
            @AuthenticationPrincipal AdminPrincipal user) {
        return queries.notifications(applicationId,status,channel,recipient,createdFrom,createdTo,page,size,sort,user.role()==AdminRole.VIEWER);
    }
    @GetMapping("/notifications/{id}") public AdminNotificationResponse notification(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user) {return queries.notification(id,user.role()==AdminRole.VIEWER);}
    @GetMapping("/stats") public AdminQueryService.Stats stats(@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) UUID applicationId) {return queries.stats(from,to,applicationId);}
    @GetMapping("/queues") public List<AdminQueryService.QueueStatus> queues() {return queries.queues();}
    @GetMapping("/applications") public List<AdminQueryService.ApplicationRow> applications() {return queries.applications();}
    @GetMapping("/audit-log") public PageResponse<AdminQueryService.AuditRow> audit(@RequestParam(required=false) UUID userId,@RequestParam(required=false) String action,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.audit(userId,action,page,size);}
}
