package dev.ricardo.notification.infrastructure.persistence;

import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PersistenceIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notification_test")
            .withUsername("notification")
            .withPassword("notification");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("Deve salvar e recuperar um template preservando caracteres utf8mb4")
    void shouldSaveAndReadTemplateWithUtf8mb4() {
        TemplateEntity template = TemplateEntity.builder()
                .code("PASSWORD_RESET")
                .channel(NotificationChannel.EMAIL)
                .subject("Redefinição de Senha - Ação Necessária")
                .content("Olá {{nome}}, utilize o código de validação para redefinir sua senha com segurança.")
                .build();

        TemplateEntity saved = templateRepository.save(template);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<TemplateEntity> found = templateRepository.findByCodeAndChannel("PASSWORD_RESET", NotificationChannel.EMAIL);
        assertThat(found).isPresent();
        assertThat(found.get().getSubject()).isEqualTo("Redefinição de Senha - Ação Necessária");
        assertThat(found.get().getContent()).contains("código de validação");
    }

    @Test
    @DisplayName("Deve rejeitar template com code e channel duplicados")
    void shouldRejectDuplicateCodeAndChannel() {
        TemplateEntity first = TemplateEntity.builder()
                .code("DUPLICATE_CODE")
                .channel(NotificationChannel.SMS)
                .content("Primeira versão do conteúdo")
                .build();

        templateRepository.saveAndFlush(first);

        TemplateEntity duplicate = TemplateEntity.builder()
                .code("DUPLICATE_CODE")
                .channel(NotificationChannel.SMS)
                .content("Segunda versão conflitante")
                .build();

        assertThatThrownBy(() -> templateRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Deve salvar e recuperar uma notificação com payload JSON e caracteres utf8mb4")
    void shouldSaveAndReadNotificationWithJsonPayloadAndUtf8mb4() {
        Map<String, String> payload = Map.of(
                "nome", "José Antônio da Silva",
                "motivo", "Operação concluída com sucesso! 🚀",
                "cidade", "São Paulo - SP"
        );

        NotificationEntity notification = NotificationEntity.builder()
                .channel(NotificationChannel.EMAIL)
                .recipient("josé.antônio@exemplo.com.br")
                .templateCode("WELCOME")
                .payload(payload)
                .status(NotificationStatus.PENDING)
                .build();

        NotificationEntity saved = notificationRepository.save(notification);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getAttempts()).isEqualTo(0);

        Optional<NotificationEntity> found = notificationRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRecipient()).isEqualTo("josé.antônio@exemplo.com.br");
        assertThat(found.get().getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(found.get().getPayload())
                .isNotNull()
                .containsEntry("nome", "José Antônio da Silva")
                .containsEntry("cidade", "São Paulo - SP")
                .containsEntry("motivo", "Operação concluída com sucesso! 🚀");
    }
}
