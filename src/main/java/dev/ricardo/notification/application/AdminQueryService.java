package dev.ricardo.notification.application;

import dev.ricardo.notification.api.*;
import dev.ricardo.notification.api.admin.AdminNotificationResponse;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminQueryService {
    private final NotificationRepository notifications;
    private final NotificationAttemptRepository attempts;
    private final AdminAuditRepository audit;
    private final JdbcTemplate jdbc;
    private final org.springframework.amqp.core.AmqpAdmin rabbit;
    @Value("${app.admin.stats-max-days:90}") private int maxStatsDays=90;
    @Value("${app.rabbitmq.queue:notification.queue}") private String mainQueue;
    @Value("${app.rabbitmq.dlq:notification.dlq}") private String dlq;
    @Value("${app.rabbitmq.retry-1-routing-key:notification.retry-1}") private String retry1;
    @Value("${app.rabbitmq.retry-2-routing-key:notification.retry-2}") private String retry2;
    @Value("${app.rabbitmq.retry-3-routing-key:notification.retry-3}") private String retry3;
    public PageResponse<AdminNotificationResponse> notifications(UUID app,List<NotificationStatus> statuses,NotificationChannel channel,String recipient,
            Instant from,Instant to,int page,int size,String order,boolean viewer) {
        Pageable pageable=page(page,size,order);
        if(from!=null&&to!=null&&from.isAfter(to)) throw new InvalidNotificationException("createdFrom must not be after createdTo");
        Specification<NotificationEntity> spec=(root,q,cb)->{
            var p=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(app!=null)p.add(cb.equal(root.get("applicationId"),app));
            if(statuses!=null&&!statuses.isEmpty())p.add(root.get("status").in(statuses));
            if(channel!=null)p.add(cb.equal(root.get("channel"),channel));
            if(recipient!=null&&!recipient.isBlank())p.add(cb.like(cb.lower(root.get("recipient")),"%"+recipient.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%",'\\'));
            if(from!=null)p.add(cb.greaterThanOrEqualTo(root.get("createdAt"),from));
            if(to!=null)p.add(cb.lessThanOrEqualTo(root.get("createdAt"),to));
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.of(notifications.findAll(spec,pageable).map(n->AdminNotificationResponse.from(n,viewer,List.of())));
    }
    public AdminNotificationResponse notification(UUID id,boolean viewer) {
        var n=notifications.findById(id).orElseThrow(()->new NotificationNotFoundException(id));
        return AdminNotificationResponse.from(n,viewer,attempts.findByNotificationIdOrderByAttemptNumberAsc(id).stream().map(NotificationAttemptResponse::from).toList());
    }
    public record TimeBucket(Instant time,long sent,long failed) {}
    public record Stats(Instant from,Instant to,long total,long sent,long failed,long queued,double successRate,double averageSendMillis,
            Map<String,Long> byStatus,Map<String,Long> byChannel,List<TimeBucket> series) {}
    public Stats stats(Instant from,Instant to,UUID app) {
        Instant end=to==null?Instant.now():to;Instant start=from==null?end.minus(Duration.ofDays(1)):from;
        if(!start.isBefore(end)||Duration.between(start,end).compareTo(Duration.ofDays(maxStatsDays))>0)
            throw new InvalidNotificationException("Statistics interval must be positive and at most "+maxStatsDays+" days");
        String where=" WHERE created_at>=? AND created_at<=?"+(app==null?"":" AND application_id=?");
        List<Object> args=new ArrayList<>(List.of(LocalDateTime.ofInstant(start,ZoneOffset.UTC),LocalDateTime.ofInstant(end,ZoneOffset.UTC)));
        if(app!=null)args.add(app.toString());Object[] params=args.toArray();
        Map<String,Long> statuses=new LinkedHashMap<>(); for(var s:NotificationStatus.values())statuses.put(s.name(),0L);
        jdbc.query("SELECT status,COUNT(*) amount FROM notification"+where+" GROUP BY status",rs->{statuses.put(rs.getString("status"),rs.getLong("amount"));},params);
        Map<String,Long> channels=new LinkedHashMap<>();for(var c:NotificationChannel.values())channels.put(c.name(),0L);
        jdbc.query("SELECT channel,COUNT(*) amount FROM notification"+where+" GROUP BY channel",rs->{channels.put(rs.getString("channel"),rs.getLong("amount"));},params);
        String format=Duration.between(start,end).compareTo(Duration.ofDays(2))<=0?"%Y-%m-%d %H:00:00":"%Y-%m-%d 00:00:00";
        var series=jdbc.query("SELECT DATE_FORMAT(created_at,'"+format+"') bucket,SUM(status='SENT') sent,SUM(status='FAILED') failed FROM notification"+where+" GROUP BY bucket ORDER BY bucket",
                (rs,row)->new TimeBucket(LocalDateTime.parse(rs.getString("bucket").replace(' ','T')).toInstant(ZoneOffset.UTC),rs.getLong("sent"),rs.getLong("failed")),params);
        Double avg=jdbc.queryForObject("SELECT AVG(TIMESTAMPDIFF(MICROSECOND,created_at,sent_at))/1000.0 FROM notification"+where+" AND status='SENT' AND sent_at IS NOT NULL",Double.class,params);
        long sent=statuses.get("SENT"),failed=statuses.get("FAILED"),total=statuses.values().stream().mapToLong(Long::longValue).sum();
        return new Stats(start,end,total,sent,failed,statuses.get("PENDING")+statuses.get("QUEUED")+statuses.get("PROCESSING"),sent+failed==0?0:100.0*sent/(sent+failed),avg==null?0:avg,statuses,channels,series);
    }
    public record QueueStatus(String name,int ready,int consumers,boolean unavailable) {}
    public List<QueueStatus> queues() {
        List<QueueStatus> result=new ArrayList<>();
        for(String name:List.of(mainQueue,retry1,retry2,retry3,dlq)) {
            try {var properties=rabbit.getQueueProperties(name);
                if(properties==null) result.add(new QueueStatus(name,0,0,true));
                else result.add(new QueueStatus(name,((Number)properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue(),((Number)properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).intValue(),false));
            }catch(RuntimeException ex){result.add(new QueueStatus(name,0,0,true));}
        }
        return result;
    }
    public record ApplicationRow(UUID id,String name,boolean active,Instant createdAt,Instant lastUsedAt,long volume7Days,Integer rateLimitPerMinute) {}
    public List<ApplicationRow> applications() {
        return jdbc.query("SELECT a.id,a.name,a.active,a.created_at,a.rate_limit_per_minute,MAX(n.created_at) last_used,SUM(CASE WHEN n.created_at>=? THEN 1 ELSE 0 END) volume FROM application a LEFT JOIN notification n ON n.application_id=a.id GROUP BY a.id ORDER BY a.name LIMIT 1000",
                (rs,row)->new ApplicationRow(UUID.fromString(rs.getString("id")),rs.getString("name"),rs.getBoolean("active"),
                        rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("last_used",LocalDateTime.class)==null?null:rs.getObject("last_used",LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getLong("volume"),(Integer)rs.getObject("rate_limit_per_minute")),LocalDateTime.ofInstant(Instant.now().minus(Duration.ofDays(7)),ZoneOffset.UTC));
    }
    public record AuditRow(UUID id,UUID adminUserId,String action,String targetType,UUID targetId,Map<String,String> details,String ip,Instant createdAt) {}
    public PageResponse<AuditRow> audit(UUID user,String action,int page,int size) {
        Specification<AdminAuditEntity> spec=(root,q,cb)->{
            var p=new ArrayList<jakarta.persistence.criteria.Predicate>();if(user!=null)p.add(cb.equal(root.get("adminUserId"),user));
            if(action!=null&&!action.isBlank())p.add(cb.equal(root.get("action"),action));return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.of(audit.findAll(spec,page(page,size,"createdAt,desc")).map(a->new AuditRow(a.getId(),a.getAdminUserId(),a.getAction(),a.getTargetType(),a.getTargetId(),a.getDetails(),a.getIp(),a.getCreatedAt())));
    }
    public static Pageable page(int page,int size,String order) {
        if(page<0||size<1||size>100)throw new InvalidNotificationException("Page must be nonnegative and size between 1 and 100");
        String[] parts=order.split(",");if(parts.length>2||!Set.of("createdAt","status","channel","attempts","id").contains(parts[0]))throw new InvalidNotificationException("Invalid sort field");
        Sort.Direction direction=parts.length==2&&"asc".equalsIgnoreCase(parts[1])?Sort.Direction.ASC:Sort.Direction.DESC;
        return PageRequest.of(page,size,Sort.by(direction,parts[0]).and(Sort.by("id")));
    }
}
