package dev.ricardo.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest {

    private final ApiKeyService apiKeyService = new ApiKeyService();

    @Test
    @DisplayName("Deve gerar chave com prefixo ntf_ e hash SHA-256 de 64 caracteres hex")
    void shouldGenerateApiKeyWithPrefixAndValidHash() {
        ApiKeyService.GeneratedApiKey key = apiKeyService.generateApiKey();

        assertThat(key.rawApiKey()).startsWith("ntf_");
        assertThat(key.rawApiKey().length()).isGreaterThan(40);
        assertThat(key.hash()).hasSize(64);
        assertThat(key.hash()).matches("^[a-f0-9]{64}$");

        // Hash deve coincidir com o hash calculado separadamente
        String calculatedHash = apiKeyService.hashApiKey(key.rawApiKey());
        assertThat(calculatedHash).isEqualTo(key.hash());
    }

    @Test
    @DisplayName("Hash da API Key deve ser determinístico")
    void hashShouldBeDeterministic() {
        String testKey = "ntf_test_api_key_1234567890_abcdef";
        String hash1 = apiKeyService.hashApiKey(testKey);
        String hash2 = apiKeyService.hashApiKey(testKey);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    @Test
    @DisplayName("Deve lançar IllegalArgumentException quando chave for nula ou em branco")
    void shouldThrowWhenKeyIsBlank() {
        assertThatThrownBy(() -> apiKeyService.hashApiKey(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> apiKeyService.hashApiKey("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
