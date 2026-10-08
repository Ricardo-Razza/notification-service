package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateNotificationRequest;
import dev.ricardo.notification.api.NotificationResponse;
import dev.ricardo.notification.domain.InvalidNotificationException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.NotificationNotFoundException;
import dev.ricardo.notification.domain.NotificationSender;
import dev.ricardo.notification.domain.NotificationStatus;
import dev.ricardo.notification.domain.RenderedMessage;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.domain.UnsupportedChannelException;
import dev.ricardo.notification.infrastructure.persistence.NotificationEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationRepository;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import dev.ricardo.notification.template.MissingTemplateVariableException;
import dev.ricardo.notification.template.TemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private dev.ricardo.notification.infrastructure.persistence.NotificationAttemptRepository attemptRepository;

    @Mock
    private TemplateRepository templateRepository;

    @Mock
    private TemplateRenderer templateRenderer;

    @Mock
    private SenderFactory senderFactory;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private NotificationSender notificationSender;

    @Mock private InputLimits inputLimits;
    @Mock private NotificationRateLimiter rateLimiter;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        // Simular execução síncrona do TransactionTemplate
        lenient().doAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("Deve enfileirar notificação com sucesso e gravar status QUEUED")
    void shouldSendNotificationSuccessfully() {
        UUID notificationId = UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo")
        );

        TemplateEntity template = TemplateEntity.builder()
                .code("WELCOME")
                .channel(NotificationChannel.EMAIL)
                .subject("Bem-vindo, {{name}}!")
                .content("Olá {{name}}, obrigado por se cadastrar.")
                .build();

        when(templateRepository.findByCodeAndChannel("WELCOME", NotificationChannel.EMAIL))
                .thenReturn(Optional.of(template));
        when(templateRenderer.render("Bem-vindo, {{name}}!", request.variables()))
                .thenReturn("Bem-vindo, Ricardo!");
        when(templateRenderer.render("Olá {{name}}, obrigado por se cadastrar.", request.variables()))
                .thenReturn("Olá Ricardo, obrigado por se cadastrar.");

        when(notificationRepository.save(any(NotificationEntity.class)))
                .thenAnswer(inv -> {
                    NotificationEntity entity = inv.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(notificationId);
                        entity.setCreatedAt(Instant.now());
                        entity.setUpdatedAt(Instant.now());
                    }
                    return entity;
                });
        when(notificationRepository.findById(notificationId))
                .thenAnswer(inv -> Optional.of(NotificationEntity.builder()
                        .id(notificationId)
                        .channel(NotificationChannel.EMAIL)
                        .recipient("ricardo@exemplo.com")
                        .templateCode("WELCOME")
                        .status(NotificationStatus.PENDING)
                        .attempts(0)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build()));

        NotificationResponse response = notificationService.sendNotification(request);

        assertThat(response.id()).isEqualTo(notificationId);
        assertThat(response.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(response.sentAt()).isNull();
        assertThat(response.attempts()).isEqualTo(0);
        assertThat(response.lastError()).isNull();

        verify(notificationPublisher).publish(notificationId);
    }

    @Test
    @DisplayName("Deve falhar com NotificationPublishException e manter status PENDING quando a publicação falhar")
    void shouldHandleQueuePublishFailureGracefully() {
        UUID notificationId = UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of("name", "Ricardo")
        );

        TemplateEntity template = TemplateEntity.builder()
                .code("WELCOME")
                .channel(NotificationChannel.EMAIL)
                .subject("Assunto")
                .content("Conteúdo")
                .build();

        when(templateRepository.findByCodeAndChannel("WELCOME", NotificationChannel.EMAIL))
                .thenReturn(Optional.of(template));
        when(templateRenderer.render(any(), any())).thenReturn("Renderizado");

        when(notificationRepository.save(any(NotificationEntity.class)))
                .thenAnswer(inv -> {
                    NotificationEntity entity = inv.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(notificationId);
                        entity.setCreatedAt(Instant.now());
                        entity.setUpdatedAt(Instant.now());
                    }
                    return entity;
                });

        doThrow(new dev.ricardo.notification.domain.NotificationPublishException("RabbitMQ connection failure"))
                .when(notificationPublisher).publish(notificationId);

        var persisted = NotificationEntity.builder().id(notificationId).channel(NotificationChannel.EMAIL)
                .recipient(request.recipient()).templateCode("WELCOME").status(NotificationStatus.PENDING).build();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(persisted));
        when(notificationRepository.findLockedById(notificationId)).thenReturn(Optional.of(persisted));

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(dev.ricardo.notification.domain.NotificationPublishException.class)
                .hasMessageContaining("RabbitMQ connection failure");

        // Publicação falhou: o estado volta a PENDING sem sobrescrever um worker em andamento.
        assertThat(persisted.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("Deve lançar TemplateNotFoundException se o template não existir")
    void shouldFailWhenTemplateNotFound() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "UNKNOWN_TEMPLATE",
                Map.of()
        );

        when(templateRepository.findByCodeAndChannel("UNKNOWN_TEMPLATE", NotificationChannel.EMAIL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("UNKNOWN_TEMPLATE");

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar MissingTemplateVariableException quando faltar variável obrigatória no payload")
    void shouldFailWhenTemplateVariableIsMissing() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of()
        );

        TemplateEntity template = TemplateEntity.builder()
                .code("WELCOME")
                .channel(NotificationChannel.EMAIL)
                .subject("Bem-vindo")
                .content("Olá {{name}}")
                .build();

        when(templateRepository.findByCodeAndChannel("WELCOME", NotificationChannel.EMAIL))
                .thenReturn(Optional.of(template));
        when(senderFactory.get(NotificationChannel.EMAIL)).thenReturn(notificationSender);
        when(templateRenderer.render("Bem-vindo", Map.of())).thenReturn("Bem-vindo");
        when(templateRenderer.render("Olá {{name}}", Map.of()))
                .thenThrow(new MissingTemplateVariableException("name"));

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(MissingTemplateVariableException.class)
                .hasMessageContaining("name");

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar UnsupportedChannelException quando o canal não possuir sender configurado")
    void shouldFailWhenChannelHasNoSender() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "+5511999999999",
                NotificationChannel.SMS,
                "SMS_ALERT",
                Map.of()
        );

        when(senderFactory.get(NotificationChannel.SMS))
                .thenThrow(new UnsupportedChannelException(NotificationChannel.SMS));

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(UnsupportedChannelException.class)
                .hasMessageContaining("SMS");

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar InvalidNotificationException quando o e-mail for inválido")
    void shouldFailWhenEmailRecipientIsInvalid() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "email-invalido-sem-arroba",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of()
        );

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(InvalidNotificationException.class)
                .hasMessageContaining("Invalid email recipient");

        verify(templateRepository, never()).findByCodeAndChannel(any(), any());
    }

    @Test
    @DisplayName("Deve lançar InvalidNotificationException quando o destinatário for nulo ou em branco")
    void shouldFailWhenRecipientIsBlank() {
        CreateNotificationRequest request = new CreateNotificationRequest(
                "   ",
                NotificationChannel.EMAIL,
                "WELCOME",
                Map.of()
        );

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(InvalidNotificationException.class)
                .hasMessageContaining("Recipient cannot be blank");
    }

    @Test
    @DisplayName("Deve buscar notificação por ID com sucesso")
    void shouldGetNotificationByIdSuccessfully() {
        UUID id = UUID.randomUUID();
        NotificationEntity entity = NotificationEntity.builder()
                .id(id)
                .channel(NotificationChannel.EMAIL)
                .recipient("ricardo@exemplo.com")
                .templateCode("WELCOME")
                .status(NotificationStatus.SENT)
                .attempts(1)
                .sentAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(entity));

        NotificationResponse response = notificationService.getNotificationById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.recipient()).isEqualTo("ricardo@exemplo.com");
    }

    @Test
    @DisplayName("Deve lançar NotificationNotFoundException quando buscar ID inexistente")
    void shouldThrowWhenNotificationNotFoundById() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotificationById(id))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Deve enviar notificação com sucesso quando o template não possuir assunto")
    void shouldSendNotificationWhenTemplateHasNoSubject() {
        UUID notificationId = UUID.randomUUID();
        CreateNotificationRequest request = new CreateNotificationRequest(
                "ricardo@exemplo.com",
                NotificationChannel.EMAIL,
                "SIMPLE_MSG",
                Map.of()
        );

        TemplateEntity template = TemplateEntity.builder()
                .code("SIMPLE_MSG")
                .channel(NotificationChannel.EMAIL)
                .subject(null)
                .content("Mensagem simples")
                .build();

        when(templateRepository.findByCodeAndChannel("SIMPLE_MSG", NotificationChannel.EMAIL))
                .thenReturn(Optional.of(template));
        when(senderFactory.get(NotificationChannel.EMAIL)).thenReturn(notificationSender);
        when(templateRenderer.render("Mensagem simples", Map.of())).thenReturn("Mensagem simples");

        when(notificationRepository.save(any(NotificationEntity.class)))
                .thenAnswer(inv -> {
                    NotificationEntity entity = inv.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(notificationId);
                        entity.setCreatedAt(Instant.now());
                        entity.setUpdatedAt(Instant.now());
                    }
                    return entity;
                });
        when(notificationRepository.findById(notificationId))
                .thenAnswer(inv -> Optional.of(NotificationEntity.builder()
                        .id(notificationId)
                        .channel(NotificationChannel.EMAIL)
                        .recipient("ricardo@exemplo.com")
                        .templateCode("SIMPLE_MSG")
                        .status(NotificationStatus.PROCESSING)
                        .attempts(0)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build()));

        NotificationResponse response = notificationService.sendNotification(request);

        assertThat(response.status()).isEqualTo(NotificationStatus.QUEUED);
        verify(notificationPublisher).publish(notificationId);
    }
}
