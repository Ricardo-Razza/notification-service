package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.rabbitmq.listener.simple.auto-startup=false","springdoc.api-docs.enabled=true","springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AdminSessionIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminUserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepository notifications;
    @Autowired ApplicationRepository applications;
    @Autowired ApiKeyService keys;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockitoBean NotificationPublisher publisher;
    @MockitoBean org.springframework.amqp.core.AmqpAdmin rabbit;
    private UUID adminId;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM admin_audit_log");jdbc.update("DELETE FROM admin_user");notifications.deleteAll();
        adminId=users.create(null,"admin@example.test","StrongPassword123!",AdminRole.ADMIN,"test").id();
        users.create(null,"viewer@example.test","StrongPassword123!",AdminRole.VIEWER,"test");
    }
    private Cookie csrf(MockHttpSession session) throws Exception {
        var request=get("/api/admin/auth/me");if(session!=null)request.session(session);
        var response=mvc.perform(request).andReturn().getResponse();var cookie=response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();assertThat(cookie.isHttpOnly()).isFalse();return cookie;
    }
    private MockHttpSession login(String email) throws Exception {
        var token=csrf(null);
        var result=mvc.perform(post("/api/admin/auth/login").cookie(token).header("X-XSRF-TOKEN",token.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new AdminAuthController.Login(email,"StrongPassword123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    @Test void validLoginMeLogoutAndCsrfRequired() throws Exception {
        var session=login("admin@example.test");
        mvc.perform(get("/api/admin/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(post("/api/admin/applications").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"denied\"}"))
                .andExpect(status().isForbidden());
        var token=csrf(session);mvc.perform(post("/api/admin/auth/logout").session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue())).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
    }
    @Test void invalidCredentialsMatchAndLockPersists() throws Exception {
        String detail=null;
        for(int i=0;i<5;i++) {
            var token=csrf(null);var result=mvc.perform(post("/api/admin/auth/login").cookie(token).header("X-XSRF-TOKEN",token.getValue())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@example.test\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().is(i==4?423:401)).andReturn();if(i==0)detail=mapper.readTree(result.getResponse().getContentAsString()).get("detail").asText();
        }
        var token=csrf(null);var unknown=mvc.perform(post("/api/admin/auth/login").cookie(token).header("X-XSRF-TOKEN",token.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"unknown@example.test\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized()).andReturn();assertThat(mapper.readTree(unknown.getResponse().getContentAsString()).get("detail").asText()).isEqualTo(detail);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM admin_user WHERE id=?",Integer.class,adminId.toString())).isEqualTo(5);
        jdbc.update("UPDATE admin_user SET locked_until='2020-01-01 00:00:00' WHERE id=?",adminId.toString());login("admin@example.test");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM admin_user WHERE id=?",Integer.class,adminId.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE action='LOGIN_FAILED'",Long.class)).isEqualTo(6);
    }
    @Test void viewerIsReadOnlyAndRecipientsAreMasked() throws Exception {
        var n=notifications.save(NotificationEntity.builder().channel(NotificationChannel.EMAIL).recipient("private@example.com").templateCode("WELCOME")
                .status(NotificationStatus.SENT).payload(Map.of("name","Test")).build());var session=login("viewer@example.test");
        mvc.perform(get("/api/admin/notifications").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].recipient").value("p***@example.com"));
        mvc.perform(get("/api/admin/notifications/"+n.getId()).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.recipient").value("p***@example.com"));
        mvc.perform(get("/api/admin/audit-log").session(session)).andExpect(status().isForbidden());
        var token=csrf(session);mvc.perform(post("/api/admin/applications").session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"forbidden\"}")).andExpect(status().isForbidden());
    }
    @Test void everyAdminMappingRejectsAnonymousRequests() throws Exception {
        int checked=0;
        for(var mapping:mappings.getHandlerMethods().keySet()) {
            var paths=mapping.getPathPatternsCondition();if(paths==null)continue;
            for(String path:paths.getPatternValues()) {
                if(!path.startsWith("/api/admin/")||path.equals("/api/admin/auth/login")||path.equals("/api/admin/auth/me"))continue;
                path=path.replaceAll("\\{[^}]+}",UUID.randomUUID().toString());
                for(var method:mapping.getMethodsCondition().getMethods()) {
                    var result=mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method.name()),path)
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
                    assertThat(result.getResponse().getStatus()).as(method+" "+path).isIn(401,403);checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(20);
    }
    @Test void lastAdminCannotBeDemotedAndOpenSessionsRevalidateRole() throws Exception {
        assertThatThrownBy(()->users.update(adminId,adminId,AdminRole.VIEWER,null,null,"test")).isInstanceOf(ScheduleConflictException.class);
        var session=login("admin@example.test");users.create(adminId,"second@example.test","StrongPassword123!",AdminRole.ADMIN,"test");
        users.update(adminId,adminId,AdminRole.VIEWER,null,null,"test");
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/auth/me").session(session)).andExpect(jsonPath("$.role").value("VIEWER"));
    }
    @Test void actionsAuditWithoutSecretsAndPreviewHasNoWrites() throws Exception {
        var session=login("admin@example.test");var token=csrf(session);
        var created=mvc.perform(post("/api/admin/applications").session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"admin-action\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").exists()).andReturn();
        String key=mapper.readTree(created.getResponse().getContentAsString()).get("apiKey").asText();
        var logs=mvc.perform(get("/api/admin/audit-log").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(logs).contains("APPLICATION_CREATED").doesNotContain(key,"passwordHash","apiKeyHash","StrongPassword123!");
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log",Long.class);
        token=csrf(session);mvc.perform(post("/api/admin/templates/preview").session(session).cookie(token).header("X-XSRF-TOKEN",token.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Hello {{name}}\",\"content\":\"{{missing}} {{name}}\",\"variables\":{\"name\":\"Test\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("Hello Test")).andExpect(jsonPath("$.missingVariables[0]").value("missing"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log",Long.class)).isEqualTo(count);
    }
    @Test void systemsRemainStatelessAndOpenApiIncludesAdminContract() throws Exception {
        var key=keys.generateApiKey();applications.save(ApplicationEntity.builder().name("regression-"+UUID.randomUUID()).apiKeyHash(key.hash()).active(true).build());
        var result=mvc.perform(get("/api/v1/templates").header("X-API-Key",key.rawApiKey())).andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        var spec=mvc.perform(get("/v3/api-docs/admin")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(spec).contains("/api/admin/auth/login","/api/admin/stats","SessionAuth").doesNotContain("/api/v1/");
    }
    @Test void knownStatisticsAndUnavailableQueuesRemainReadable() throws Exception {
        var key=keys.generateApiKey();var app=applications.save(ApplicationEntity.builder().name("stats-"+UUID.randomUUID()).apiKeyHash(key.hash()).active(true).build());
        Instant base=Instant.now().minusSeconds(60);
        notifications.save(NotificationEntity.builder().applicationId(app.getId()).channel(NotificationChannel.EMAIL).recipient("a@example.com").templateCode("WELCOME")
                .status(NotificationStatus.SENT).createdAt(base).sentAt(base.plusSeconds(5)).build());
        notifications.save(NotificationEntity.builder().applicationId(app.getId()).channel(NotificationChannel.EMAIL).recipient("b@example.com").templateCode("WELCOME")
                .status(NotificationStatus.FAILED).createdAt(base).build());
        var session=login("viewer@example.test");
        mvc.perform(get("/api/admin/stats").session(session).param("applicationId",app.getId().toString()).param("from",base.minusSeconds(60).toString()).param("to",base.plusSeconds(60).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.sent").value(1))
                .andExpect(jsonPath("$.failed").value(1)).andExpect(jsonPath("$.successRate").value(50.0)).andExpect(jsonPath("$.averageSendMillis").value(5000.0));
        org.mockito.Mockito.when(rabbit.getQueueProperties(org.mockito.ArgumentMatchers.anyString())).thenThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException()));
        mvc.perform(get("/api/admin/queues").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].unavailable").value(true));
    }
}
