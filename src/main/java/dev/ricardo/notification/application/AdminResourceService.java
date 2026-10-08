package dev.ricardo.notification.application;

import dev.ricardo.notification.api.*;
import dev.ricardo.notification.api.admin.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import dev.ricardo.notification.scheduler.ScheduleTimeCalculator;
import dev.ricardo.notification.security.AdminPrincipal;
import dev.ricardo.notification.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service @RequiredArgsConstructor
public class AdminResourceService {
    private final TemplateRepository templates;
    private final TemplateService templateService;
    private final NotificationScheduleRepository schedules;
    private final ScheduleService scheduleService;
    private final ApplicationService applications;
    private final ApplicationRepository applicationRepository;
    private final AdminAuditService audit;
    private final InputLimits limits;
    private final TemplateRenderer renderer;
    private final ScheduleTimeCalculator calculator;
    @Transactional(readOnly=true)
    public PageResponse<AdminTemplateResponse> templates(UUID app,NotificationChannel channel,String scope,int page,int size) {
        Specification<TemplateEntity> spec=(root,q,cb)->{
            var p=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(app!=null)p.add(cb.equal(root.get("applicationId"),app));
            if("GLOBAL".equals(scope))p.add(cb.isNull(root.get("applicationId")));
            if("APPLICATION".equals(scope))p.add(cb.isNotNull(root.get("applicationId")));
            if(channel!=null)p.add(cb.equal(root.get("channel"),channel));return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        AdminQueryService.page(page,size,"id,asc");return PageResponse.of(templates.findAll(spec,PageRequest.of(page,size,Sort.by("code","id"))).map(AdminTemplateResponse::from));
    }
    @Transactional(readOnly=true) public AdminTemplateResponse template(UUID id) {return AdminTemplateResponse.from(templateEntity(id));}
    private TemplateEntity templateEntity(UUID id) {return templates.findById(id).orElseThrow(()->new TemplateNotFoundException(id));}
    @Transactional public AdminTemplateResponse createTemplate(UUID app,CreateTemplateRequest request,AdminPrincipal user,String ip) {
        if(app!=null)applicationRepository.findById(app).orElseThrow(()->new ApplicationNotFoundException(app));
        var result=templateService.createTemplate(request,app);audit.record(user.id(),"TEMPLATE_CREATED","TEMPLATE",result.id(),Map.of(),ip);return template(result.id());
    }
    @Transactional public AdminTemplateResponse updateTemplate(UUID id,UpdateTemplateRequest request,AdminPrincipal user,String ip) {
        templateService.updateTemplate(id,request);audit.record(user.id(),"TEMPLATE_UPDATED","TEMPLATE",id,Map.of(),ip);return template(id);
    }
    @Transactional public void deleteTemplate(UUID id,AdminPrincipal user,String ip) {templates.delete(templateEntity(id));audit.record(user.id(),"TEMPLATE_DELETED","TEMPLATE",id,Map.of(),ip);}
    public record Preview(String subject,String content,List<String> variables,List<String> missingVariables) {}
    public Preview preview(String subject,String content,Map<String,String> variables) {
        limits.template(subject,content);var values=new HashMap<>(variables==null?Map.of():variables);limits.variables(values);
        values.putIfAbsent("today",LocalDate.now(ZoneOffset.UTC).toString());values.putIfAbsent("now",Instant.now().toString());
        var names=new LinkedHashSet<String>();var pattern=Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
        for(String text:List.of(subject==null?"":subject,content)) {var matcher=pattern.matcher(text);while(matcher.find())names.add(matcher.group(1));}
        var missing=names.stream().filter(name->!values.containsKey(name)).toList();for(String name:missing)values.put(name,"{{"+name+"}}");
        return new Preview(subject==null?null:renderer.render(subject,values),renderer.render(content,values),List.copyOf(names),missing);
    }
    @Transactional public ApplicationCreatedResponse createApplication(CreateApplicationRequest request,AdminPrincipal user,String ip) {
        var result=applications.createApplication(request);audit.record(user.id(),"APPLICATION_CREATED","APPLICATION",result.id(),Map.of(),ip);return result;
    }
    @Transactional public ApplicationResponse active(UUID id,boolean active,AdminPrincipal user,String ip) {
        var result=active?applications.activate(id):applications.deactivate(id);audit.record(user.id(),active?"APPLICATION_ACTIVATED":"APPLICATION_DEACTIVATED","APPLICATION",id,Map.of(),ip);return result;
    }
    @Transactional public ApplicationCreatedResponse rotate(UUID id,AdminPrincipal user,String ip) {
        var result=applications.rotateKey(id);audit.record(user.id(),"KEY_ROTATED","APPLICATION",id,Map.of(),ip);return result;
    }
    @Transactional public ApplicationResponse revoke(UUID id,AdminPrincipal user,String ip) {
        var result=applications.revokePreviousKey(id);audit.record(user.id(),"KEY_REVOKED","APPLICATION",id,Map.of(),ip);return result;
    }
    @Transactional public ApplicationResponse rate(UUID id,Integer rate,AdminPrincipal user,String ip) {
        var result=applications.updateRateLimit(id,rate);audit.record(user.id(),"APPLICATION_RATE_UPDATED","APPLICATION",id,Map.of("limit",rate==null?"default":rate.toString()),ip);return result;
    }
    @Transactional(readOnly=true) public PageResponse<AdminScheduleResponse> schedules(UUID app,Boolean active,int page,int size,boolean viewer) {
        Specification<NotificationScheduleEntity> spec=(root,q,cb)->{
            var p=new ArrayList<jakarta.persistence.criteria.Predicate>();if(app!=null)p.add(cb.equal(root.get("applicationId"),app));
            if(active!=null)p.add(cb.equal(root.get("active"),active));return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.of(schedules.findAll(spec,AdminQueryService.page(page,size,"createdAt,desc")).map(s->AdminScheduleResponse.from(s,viewer)));
    }
    @Transactional(readOnly=true) public AdminScheduleResponse schedule(UUID id,boolean viewer) {return AdminScheduleResponse.from(scheduleEntity(id),viewer);}
    private NotificationScheduleEntity scheduleEntity(UUID id) {return schedules.findById(id).orElseThrow(()->new ScheduleNotFoundException("Schedule not found"));}
    @Transactional public AdminScheduleResponse createSchedule(UUID app,CreateScheduleRequest request,AdminPrincipal user,String ip) {
        var result=scheduleService.createSchedule(request,app);audit.record(user.id(),"SCHEDULE_CREATED","SCHEDULE",result.id(),Map.of(),ip);return schedule(result.id(),false);
    }
    @Transactional public AdminScheduleResponse updateSchedule(UUID id,UpdateScheduleRequest request,AdminPrincipal user,String ip) {
        var existing=scheduleEntity(id);scheduleService.updateSchedule(id,request,existing.getApplicationId());audit.record(user.id(),"SCHEDULE_UPDATED","SCHEDULE",id,Map.of(),ip);return schedule(id,false);
    }
    @Transactional public AdminScheduleResponse activeSchedule(UUID id,boolean active,AdminPrincipal user,String ip) {
        var existing=scheduleEntity(id);
        if(active)scheduleService.resumeSchedule(id,existing.getApplicationId());else scheduleService.pauseSchedule(id,existing.getApplicationId());
        audit.record(user.id(),active?"SCHEDULE_RESUMED":"SCHEDULE_PAUSED","SCHEDULE",id,Map.of(),ip);return schedule(id,false);
    }
    @Transactional public void deleteSchedule(UUID id,AdminPrincipal user,String ip) {
        var existing=scheduleEntity(id);scheduleService.deleteSchedule(id,existing.getApplicationId());audit.record(user.id(),"SCHEDULE_DELETED","SCHEDULE",id,Map.of(),ip);
    }
    public record Occurrence(Instant utc,String local,String timezone) {}
    public record CronPreview(List<Occurrence> occurrences,boolean daylightSavingTransition) {}
    public CronPreview cron(String cron,String timezone,int count) {
        if(count<1||count>10)throw new InvalidScheduleException("Count must be between 1 and 10");
        calculator.parseAndValidateCron(cron);var zone=calculator.parseAndValidateTimezone(timezone);Instant now=Instant.now(),cursor=now;
        var result=new ArrayList<Occurrence>();for(int i=0;i<count;i++) {cursor=calculator.calculateNextRun(cron,timezone,cursor);if(cursor==null)break;result.add(new Occurrence(cursor,cursor.atZone(zone).toString(),timezone));}
        var transition=zone.getRules().nextTransition(now);boolean dst=transition!=null&&cursor!=null&&!transition.getInstant().isAfter(cursor);
        return new CronPreview(result,dst);
    }
}
