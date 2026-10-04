package dev.ricardo.notification.application;

import dev.ricardo.notification.api.admin.ApplicationCreatedResponse;
import dev.ricardo.notification.api.admin.ApplicationResponse;
import dev.ricardo.notification.api.admin.CreateApplicationRequest;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.domain.ApplicationNotFoundException;
import dev.ricardo.notification.domain.DuplicateApplicationNameException;
import dev.ricardo.notification.infrastructure.persistence.ApplicationEntity;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private ApiKeyService apiKeyService;

    @InjectMocks
    private ApplicationService applicationService;

    @Test
    @DisplayName("Deve criar aplicação com chave e gravar hash")
    void shouldCreateApplicationSuccessfully() {
        CreateApplicationRequest request = new CreateApplicationRequest("Billing Service");
        UUID id = UUID.randomUUID();

        when(applicationRepository.existsByName("Billing Service")).thenReturn(false);
        when(apiKeyService.generateApiKey()).thenReturn(new ApiKeyService.GeneratedApiKey("ntf_raw_key", "hash_sha256_hex"));

        when(applicationRepository.save(any(ApplicationEntity.class))).thenAnswer(inv -> {
            ApplicationEntity entity = inv.getArgument(0);
            entity.setId(id);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return entity;
        });

        ApplicationCreatedResponse response = applicationService.createApplication(request);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Billing Service");
        assertThat(response.apiKey()).isEqualTo("ntf_raw_key");
        assertThat(response.active()).isTrue();
    }

    @Test
    @DisplayName("Deve lançar DuplicateApplicationNameException ao cadastrar nome duplicado")
    void shouldFailWhenNameIsDuplicate() {
        CreateApplicationRequest request = new CreateApplicationRequest("Billing Service");
        when(applicationRepository.existsByName("Billing Service")).thenReturn(true);

        assertThatThrownBy(() -> applicationService.createApplication(request))
                .isInstanceOf(DuplicateApplicationNameException.class)
                .hasMessageContaining("Billing Service");
    }

    @Test
    @DisplayName("Deve desativar aplicação existente")
    void shouldDeactivateApplication() {
        UUID id = UUID.randomUUID();
        ApplicationEntity entity = ApplicationEntity.builder()
                .id(id)
                .name("App")
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(applicationRepository.findById(id)).thenReturn(Optional.of(entity));
        when(applicationRepository.save(any(ApplicationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplicationResponse response = applicationService.deactivate(id);

        assertThat(response.active()).isFalse();
        verify(applicationRepository).save(entity);
    }

    @Test
    @DisplayName("Deve ativar aplicação inativa")
    void shouldActivateApplication() {
        UUID id = UUID.randomUUID();
        ApplicationEntity entity = ApplicationEntity.builder()
                .id(id)
                .name("App")
                .active(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(applicationRepository.findById(id)).thenReturn(Optional.of(entity));
        when(applicationRepository.save(any(ApplicationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplicationResponse response = applicationService.activate(id);

        assertThat(response.active()).isTrue();
        verify(applicationRepository).save(entity);
    }

    @Test
    @DisplayName("Deve lançar ApplicationNotFoundException ao buscar ID inexistente")
    void shouldFailWhenApplicationNotFound() {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicationService.deactivate(id))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Deve listar aplicações")
    void shouldListApplications() {
        ApplicationEntity app = ApplicationEntity.builder()
                .id(UUID.randomUUID())
                .name("App 1")
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(applicationRepository.findAll()).thenReturn(List.of(app));

        List<ApplicationResponse> list = applicationService.listApplications();

        assertThat(list).hasSize(1);
        assertThat(list.getFirst().name()).isEqualTo("App 1");
    }
}
