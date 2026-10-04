package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateTemplateRequest;
import dev.ricardo.notification.api.TemplateResponse;
import dev.ricardo.notification.api.UpdateTemplateRequest;
import dev.ricardo.notification.domain.DuplicateTemplateException;
import dev.ricardo.notification.domain.InvalidTemplateException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private TemplateRepository templateRepository;

    @InjectMocks
    private TemplateService templateService;

    @Test
    @DisplayName("Deve criar template EMAIL com sucesso quando o assunto for fornecido")
    void shouldCreateEmailTemplateSuccessfully() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "VERIFY_EMAIL",
                NotificationChannel.EMAIL,
                "Seu código de verificação",
                "Olá, use o código {{code}}."
        );

        when(templateRepository.existsByCodeAndChannel("VERIFY_EMAIL", NotificationChannel.EMAIL))
                .thenReturn(false);

        UUID generatedId = UUID.randomUUID();
        when(templateRepository.save(any(TemplateEntity.class)))
                .thenAnswer(inv -> {
                    TemplateEntity entity = inv.getArgument(0);
                    entity.setId(generatedId);
                    entity.setCreatedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());
                    return entity;
                });

        TemplateResponse response = templateService.createTemplate(request);

        assertThat(response.id()).isEqualTo(generatedId);
        assertThat(response.code()).isEqualTo("VERIFY_EMAIL");
        assertThat(response.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(response.subject()).isEqualTo("Seu código de verificação");
        assertThat(response.content()).isEqualTo("Olá, use o código {{code}}.");
        verify(templateRepository).save(any(TemplateEntity.class));
    }

    @Test
    @DisplayName("Deve criar template SMS com sucesso mesmo sem assunto")
    void shouldCreateSmsTemplateWithoutSubject() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "SMS_ALERT",
                NotificationChannel.SMS,
                null,
                "Alerta: seu saldo está baixo."
        );

        when(templateRepository.existsByCodeAndChannel("SMS_ALERT", NotificationChannel.SMS))
                .thenReturn(false);

        when(templateRepository.save(any(TemplateEntity.class)))
                .thenAnswer(inv -> {
                    TemplateEntity entity = inv.getArgument(0);
                    entity.setId(UUID.randomUUID());
                    entity.setCreatedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());
                    return entity;
                });

        TemplateResponse response = templateService.createTemplate(request);

        assertThat(response.channel()).isEqualTo(NotificationChannel.SMS);
        assertThat(response.subject()).isNull();
        verify(templateRepository).save(any(TemplateEntity.class));
    }

    @Test
    @DisplayName("Deve lançar InvalidTemplateException ao criar template EMAIL sem assunto")
    void shouldFailToCreateEmailTemplateWithoutSubject() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "VERIFY_EMAIL",
                NotificationChannel.EMAIL,
                "   ",
                "Conteúdo qualquer"
        );

        assertThatThrownBy(() -> templateService.createTemplate(request))
                .isInstanceOf(InvalidTemplateException.class)
                .hasMessageContaining("Subject is required for EMAIL channel");

        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar DuplicateTemplateException quando code e channel já existirem")
    void shouldFailToCreateDuplicateTemplate() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "DUPLICATE_CODE",
                NotificationChannel.PUSH,
                null,
                "Conteúdo"
        );

        when(templateRepository.existsByCodeAndChannel("DUPLICATE_CODE", NotificationChannel.PUSH))
                .thenReturn(true);

        assertThatThrownBy(() -> templateService.createTemplate(request))
                .isInstanceOf(DuplicateTemplateException.class)
                .hasMessageContaining("already exists");

        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve buscar template por ID com sucesso")
    void shouldGetTemplateById() {
        UUID id = UUID.randomUUID();
        TemplateEntity entity = TemplateEntity.builder()
                .id(id)
                .code("LOGIN_ALERT")
                .channel(NotificationChannel.EMAIL)
                .subject("Novo login detectado")
                .content("Detectamos um novo login.")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(templateRepository.findById(id)).thenReturn(Optional.of(entity));

        TemplateResponse response = templateService.getTemplateById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.code()).isEqualTo("LOGIN_ALERT");
    }

    @Test
    @DisplayName("Deve lançar TemplateNotFoundException ao buscar ID inexistente")
    void shouldFailWhenTemplateNotFound() {
        UUID id = UUID.randomUUID();
        when(templateRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.getTemplateById(id))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Deve listar templates filtrando por canal quando fornecido")
    void shouldListTemplatesByChannel() {
        TemplateEntity entity = TemplateEntity.builder()
                .id(UUID.randomUUID())
                .code("SMS_1")
                .channel(NotificationChannel.SMS)
                .content("Mensagem")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(templateRepository.findByChannel(NotificationChannel.SMS))
                .thenReturn(List.of(entity));

        List<TemplateResponse> responses = templateService.listTemplates(NotificationChannel.SMS);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().channel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("Deve atualizar subject e content com sucesso sem alterar code e channel")
    void shouldUpdateTemplateSuccessfully() {
        UUID id = UUID.randomUUID();
        TemplateEntity entity = TemplateEntity.builder()
                .id(id)
                .code("WELCOME")
                .channel(NotificationChannel.EMAIL)
                .subject("Assunto antigo")
                .content("Conteúdo antigo")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(templateRepository.findById(id)).thenReturn(Optional.of(entity));
        when(templateRepository.save(any(TemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTemplateRequest updateRequest = new UpdateTemplateRequest("Novo Assunto", "Novo Conteúdo");

        TemplateResponse response = templateService.updateTemplate(id, updateRequest);

        assertThat(response.subject()).isEqualTo("Novo Assunto");
        assertThat(response.content()).isEqualTo("Novo Conteúdo");
        assertThat(response.code()).isEqualTo("WELCOME");
        assertThat(response.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("Deve lançar TemplateNotFoundException ao tentar atualizar ID inexistente")
    void shouldFailToUpdateWhenTemplateNotFound() {
        UUID id = UUID.randomUUID();
        when(templateRepository.findById(id)).thenReturn(Optional.empty());

        UpdateTemplateRequest request = new UpdateTemplateRequest("Novo Assunto", "Novo Conteúdo");

        assertThatThrownBy(() -> templateService.updateTemplate(id, request))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar InvalidTemplateException ao atualizar template EMAIL com assunto em branco")
    void shouldFailToUpdateEmailTemplateWithBlankSubject() {
        UUID id = UUID.randomUUID();
        TemplateEntity entity = TemplateEntity.builder()
                .id(id)
                .code("WELCOME")
                .channel(NotificationChannel.EMAIL)
                .subject("Assunto")
                .content("Conteúdo")
                .build();

        when(templateRepository.findById(id)).thenReturn(Optional.of(entity));

        UpdateTemplateRequest request = new UpdateTemplateRequest("   ", "Novo Conteúdo");

        assertThatThrownBy(() -> templateService.updateTemplate(id, request))
                .isInstanceOf(InvalidTemplateException.class)
                .hasMessageContaining("Subject is required for EMAIL channel");

        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve listar todos os templates quando canal for nulo")
    void shouldListAllTemplatesWhenChannelIsNull() {
        TemplateEntity entity = TemplateEntity.builder()
                .id(UUID.randomUUID())
                .code("WELCOME")
                .channel(NotificationChannel.EMAIL)
                .subject("Assunto")
                .content("Conteúdo")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(templateRepository.findAll()).thenReturn(List.of(entity));

        List<TemplateResponse> responses = templateService.listTemplates(null);

        assertThat(responses).hasSize(1);
        verify(templateRepository).findAll();
        verify(templateRepository, never()).findByChannel(any());
    }
}
