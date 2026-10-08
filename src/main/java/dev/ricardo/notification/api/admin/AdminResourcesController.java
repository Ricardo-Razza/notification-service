package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.*;
import dev.ricardo.notification.api.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name="Admin resources")
public class AdminResourcesController {
    private final AdminResourceService service;
    public record TemplateCreate(UUID applicationId,@NotBlank @Pattern(regexp="^[A-Z0-9_]+$") @Size(max=100) String code,
            @NotNull NotificationChannel channel,String subject,@NotBlank String content) {}
    public record TemplatePreview(String subject,@NotBlank String content,Map<String,String> variables) {}
    public record Rate(Integer rateLimitPerMinute) {}
    public record ScheduleCreate(@NotNull UUID applicationId,@NotNull @Valid CreateScheduleRequest schedule) {}
    public record Cron(@NotBlank String cron,@NotBlank String timezone,@Min(1) @Max(10) Integer count) {}
    @GetMapping("/templates") public PageResponse<AdminTemplateResponse> templates(@RequestParam(required=false) UUID applicationId,
            @RequestParam(required=false) NotificationChannel channel,@RequestParam(defaultValue="ALL") String scope,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.templates(applicationId,channel,scope,page,size);}
    @GetMapping("/templates/{id}") public AdminTemplateResponse template(@PathVariable("id") UUID id) {return service.template(id);}
    @PostMapping("/templates") public AdminTemplateResponse createTemplate(@Valid @RequestBody TemplateCreate request,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {
        return service.createTemplate(request.applicationId(),new CreateTemplateRequest(request.code(),request.channel(),request.subject(),request.content()),user,http.getRemoteAddr());
    }
    @PutMapping("/templates/{id}") public AdminTemplateResponse updateTemplate(@PathVariable("id") UUID id,@Valid @RequestBody UpdateTemplateRequest request,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.updateTemplate(id,request,user,http.getRemoteAddr());}
    @DeleteMapping("/templates/{id}") public void deleteTemplate(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {service.deleteTemplate(id,user,http.getRemoteAddr());}
    @PostMapping("/templates/preview") public AdminResourceService.Preview preview(@Valid @RequestBody TemplatePreview request) {return service.preview(request.subject(),request.content(),request.variables());}
    @PostMapping("/applications") public ApplicationCreatedResponse createApplication(@Valid @RequestBody CreateApplicationRequest request,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.createApplication(request,user,http.getRemoteAddr());}
    @PostMapping("/applications/{id}/activate") public ApplicationResponse activate(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.active(id,true,user,http.getRemoteAddr());}
    @PostMapping("/applications/{id}/deactivate") public ApplicationResponse deactivate(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.active(id,false,user,http.getRemoteAddr());}
    @PostMapping("/applications/{id}/rotate-key") public ApplicationCreatedResponse rotate(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.rotate(id,user,http.getRemoteAddr());}
    @PostMapping("/applications/{id}/revoke-previous-key") public ApplicationResponse revoke(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.revoke(id,user,http.getRemoteAddr());}
    @PatchMapping("/applications/{id}") public ApplicationResponse rate(@PathVariable("id") UUID id,@RequestBody Rate request,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.rate(id,request.rateLimitPerMinute(),user,http.getRemoteAddr());}
    @GetMapping("/schedules") public PageResponse<AdminScheduleResponse> schedules(@RequestParam(required=false) UUID applicationId,@RequestParam(required=false) Boolean active,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal AdminPrincipal user) {return service.schedules(applicationId,active,page,size,user.role()==AdminRole.VIEWER);}
    @GetMapping("/schedules/{id}") public AdminScheduleResponse schedule(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user) {return service.schedule(id,user.role()==AdminRole.VIEWER);}
    @PostMapping("/schedules") public AdminScheduleResponse createSchedule(@Valid @RequestBody ScheduleCreate request,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.createSchedule(request.applicationId(),request.schedule(),user,http.getRemoteAddr());}
    @PutMapping("/schedules/{id}") public AdminScheduleResponse updateSchedule(@PathVariable("id") UUID id,@Valid @RequestBody UpdateScheduleRequest request,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.updateSchedule(id,request,user,http.getRemoteAddr());}
    @PostMapping("/schedules/{id}/pause") public AdminScheduleResponse pause(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.activeSchedule(id,false,user,http.getRemoteAddr());}
    @PostMapping("/schedules/{id}/resume") public AdminScheduleResponse resume(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {return service.activeSchedule(id,true,user,http.getRemoteAddr());}
    @DeleteMapping("/schedules/{id}") public void deleteSchedule(@PathVariable("id") UUID id,@AuthenticationPrincipal AdminPrincipal user,HttpServletRequest http) {service.deleteSchedule(id,user,http.getRemoteAddr());}
    @PostMapping("/schedules/preview-cron") public AdminResourceService.CronPreview cron(@Valid @RequestBody Cron request) {return service.cron(request.cron(),request.timezone(),request.count()==null?5:request.count());}
}
