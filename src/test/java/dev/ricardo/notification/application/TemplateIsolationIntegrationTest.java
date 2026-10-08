package dev.ricardo.notification.application;

import dev.ricardo.notification.api.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.infrastructure.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "app.scheduler.enabled=false",
        "management.health.rabbit.enabled=false", "management.health.mail.enabled=false"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TemplateIsolationIntegrationTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired TemplateService service;
    @Autowired TemplateRepository templates;
    @Autowired ApplicationRepository applications;
    @Autowired ApiKeyService keys;
    @Autowired NotificationService notifications;
    @Autowired MockMvc mvc;
    @MockitoBean NotificationPublisher publisher;

    private UUID application() {
        return applications.save(ApplicationEntity.builder().name("scope-" + UUID.randomUUID())
                .apiKeyHash(keys.generateApiKey().hash()).active(true).build()).getId();
    }
    @Test void isolatesOwnedTemplatesAndReadsGlobalFallback() {
        var a = application(); var b = application();
        var request = new CreateTemplateRequest("SCOPE_TEST", NotificationChannel.EMAIL, "Hello", "Body");
        var owned = service.createTemplate(request, a);
        var other = service.createTemplate(request, b);
        assertThat(owned.id()).isNotEqualTo(other.id());
        assertThatThrownBy(() -> service.getTemplateById(owned.id(), b)).isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(() -> service.updateTemplate(owned.id(), new UpdateTemplateRequest("X", "Y"), b))
                .isInstanceOf(TemplateNotFoundException.class);
        assertThat(service.listTemplates(null, b)).extracting(TemplateResponse::id).contains(other.id()).doesNotContain(owned.id());
        var global = templates.findByCodeAndChannel("WELCOME", NotificationChannel.EMAIL).orElseThrow();
        assertThat(service.getTemplateById(global.getId(), a).id()).isEqualTo(global.getId());
        assertThatThrownBy(() -> service.updateTemplate(global.getId(), new UpdateTemplateRequest("X", "Y"), a))
                .isInstanceOf(GlobalTemplateReadOnlyException.class);
        assertThat(templates.findVisible(a, "SCOPE_TEST", NotificationChannel.EMAIL).orElseThrow().getId()).isEqualTo(owned.id());
        assertThat(templates.findVisible(a, "WELCOME", NotificationChannel.EMAIL).orElseThrow().getApplicationId()).isNull();
        assertThat(notifications.sendNotification(new CreateNotificationRequest("test@example.com", NotificationChannel.EMAIL,
                "WELCOME", Map.of("name", "Test")), a).status()).isEqualTo(NotificationStatus.QUEUED);
    }
    @Test void healthDoesNotExposeComponents() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
    }
    @Test void swaggerIsAbsentWhenDisabled() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
