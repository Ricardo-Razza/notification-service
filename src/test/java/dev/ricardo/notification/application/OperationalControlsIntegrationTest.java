package dev.ricardo.notification.application;

import dev.ricardo.notification.api.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import dev.ricardo.notification.scheduler.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.rabbitmq.listener.simple.auto-startup=false", "app.retention.batch-size=2", "app.retention.pause-ms=0"})
@AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OperationalControlsIntegrationTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl); r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired NotificationRepository notifications;
    @Autowired NotificationAttemptRepository attempts;
    @Autowired ApplicationRepository applications;
    @Autowired NotificationReconciler reconciler;
    @Autowired NotificationRetention retention;
    @Autowired ApplicationService applicationService;
    @Autowired ApiKeyService keys;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationPublisher publisher;
    @BeforeEach void clean() { attempts.deleteAll(); notifications.deleteAll(); reset(publisher); }
    private NotificationEntity notification(NotificationStatus status, int ageDays) {
        var n = notifications.saveAndFlush(NotificationEntity.builder().channel(NotificationChannel.EMAIL).recipient("private@example.com")
                .templateCode("WELCOME").payload(Map.of("name","private")).status(status)
                .createdAt(Instant.now().minus(Duration.ofDays(ageDays))).build());
        jdbc.update("UPDATE notification SET updated_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1800)), n.getId().toString());
        return n;
    }
    @Test void recoversAllStalledStatesOnlyAfterCommit() {
        var pending=notification(NotificationStatus.PENDING, 0); var queued=notification(NotificationStatus.QUEUED, 0);
        var processing=notification(NotificationStatus.PROCESSING, 0); var sent=notification(NotificationStatus.SENT, 0);
        doAnswer(inv -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return null; }).when(publisher).publish(any());
        assertThat(reconciler.reconcile()).isEqualTo(3);
        assertThat(notifications.findById(processing.getId()).orElseThrow().getAttempts()).isEqualTo(1);
        assertThat(attempts.findByNotificationIdOrderByAttemptNumberAsc(processing.getId())).hasSize(1);
        verify(publisher).publish(pending.getId()); verify(publisher).publish(queued.getId()); verify(publisher).publish(processing.getId());
        verify(publisher, never()).publish(sent.getId()); assertThat(reconciler.reconcile()).isZero();
    }
    @Test void concurrentRecoveryDoesNotPublishTwice() throws Exception {
        var n=notification(NotificationStatus.PENDING, 0);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=executor.submit(() -> {gate.await(); return reconciler.reconcile();});
            var b=executor.submit(() -> {gate.await(); return reconciler.reconcile();});
            gate.countDown(); assertThat(a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS)).isEqualTo(1);
        }
        verify(publisher,times(1)).publish(n.getId());
    }
    @Test void recoveryLimitAndRecentRowsAreRespected() {
        var n=notification(NotificationStatus.PENDING, 0);
        jdbc.update("UPDATE notification SET recovery_count=5 WHERE id=?",n.getId().toString());
        var recent=notifications.saveAndFlush(NotificationEntity.builder().channel(NotificationChannel.EMAIL).recipient("new@example.com")
                .templateCode("WELCOME").status(NotificationStatus.PENDING).build());
        assertThat(reconciler.reconcile()).isZero();
        assertThat(notifications.findById(n.getId()).orElseThrow().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notifications.findById(recent.getId()).orElseThrow().getStatus()).isEqualTo(NotificationStatus.PENDING);
        verifyNoInteractions(publisher);
    }
    @Test void retentionUsesBatchesAndAnonymizesOnce() {
        var sent=notification(NotificationStatus.SENT, 40); var failed=notification(NotificationStatus.FAILED,40);
        notification(NotificationStatus.CANCELED,100); notification(NotificationStatus.SENT,100); notification(NotificationStatus.FAILED,100);
        var scheduled=notification(NotificationStatus.SCHEDULED,100);
        var result=retention.run(); assertThat(result.deleted()).isEqualTo(3); assertThat(result.anonymized()).isEqualTo(2);
        var anonymous=notifications.findById(sent.getId()).orElseThrow(); assertThat(anonymous.getRecipient()).isEqualTo("p***@example.com");
        assertThat(anonymous.getPayload()).isNull(); assertThat(anonymous.getAnonymizedAt()).isNotNull();
        assertThat(notifications.findById(scheduled.getId()).orElseThrow().getPayload()).isNotEmpty();
        assertThat(retention.run()).isEqualTo(new NotificationRetention.Result(0,0));
    }
    private record App(UUID id, String key) {}
    private App application(Integer rate) {
        var key=keys.generateApiKey(); var app=applications.saveAndFlush(ApplicationEntity.builder().name("ops-"+UUID.randomUUID())
                .apiKeyHash(key.hash()).active(true).rateLimitPerMinute(rate).build()); return new App(app.getId(),key.rawApiKey());
    }
    private CreateNotificationRequest request(String name) {
        return new CreateNotificationRequest("test@example.com",NotificationChannel.EMAIL,"WELCOME",Map.of("name",name));
    }
    @Test void enforcesIdempotencyContentAndBatchPartialResults() throws Exception {
        var app=application(null);
        mvc.perform(post("/api/v1/notifications").header("X-API-Key",app.key()).header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request("one")))).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/notifications").header("X-API-Key",app.key()).header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request("two")))).andExpect(status().isUnprocessableEntity());
        var item=new BatchNotificationController.Item("test@example.com",NotificationChannel.EMAIL,"WELCOME",Map.of("name","batch"),null,"batch-key");
        var invalid=new BatchNotificationController.Item("",NotificationChannel.EMAIL,"WELCOME",Map.of(),null,null);
        mvc.perform(post("/api/v1/notifications/batch").header("X-API-Key",app.key()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new BatchNotificationController.Request(List.of(item,item,invalid)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("created"))
                .andExpect(jsonPath("$[1].status").value("replayed")).andExpect(jsonPath("$[2].status").value("failed"));
    }
    @Test void rateLimitIsIsolatedAndHasRetryAfter() throws Exception {
        var limited=application(1); var other=application(null); String json=mapper.writeValueAsString(request("limit"));
        mvc.perform(post("/api/v1/notifications").header("X-API-Key",limited.key()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/notifications").header("X-API-Key",limited.key()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(post("/api/v1/notifications").header("X-API-Key",other.key()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/notifications").header("X-API-Key",other.key()).contentType(MediaType.APPLICATION_JSON).content("x".repeat(102401)))
                .andExpect(status().isPayloadTooLarge());
    }
    @Test void previousKeyExpiresAndCanBeRevoked() throws Exception {
        var app=application(null); var rotated=applicationService.rotateKey(app.id());
        mvc.perform(get("/api/v1/templates").header("X-API-Key",app.key())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/templates").header("X-API-Key",rotated.apiKey())).andExpect(status().isOk());
        jdbc.update("UPDATE application SET previous_key_expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),app.id().toString());
        mvc.perform(get("/api/v1/templates").header("X-API-Key",app.key())).andExpect(status().isUnauthorized());
        var again=applicationService.rotateKey(app.id()); applicationService.revokePreviousKey(app.id());
        mvc.perform(get("/api/v1/templates").header("X-API-Key",rotated.apiKey())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/templates").header("X-API-Key",again.apiKey())).andExpect(status().isOk());
    }
}
