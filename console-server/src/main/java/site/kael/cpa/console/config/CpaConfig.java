package site.kael.cpa.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.kael.cpa.console.core.cpa.client.CpaApiClient;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;

import java.time.Duration;

@Configuration
public class CpaConfig {
    @Bean
    public ApiKeyCrypto apiKeyCrypto(
            @Value("${cpa.console.api-key-encryption-key:${CPA_CONSOLE_API_KEY_ENCRYPTION_KEY:}}") String encryptionKey
    ) {
        return new ApiKeyCrypto(encryptionKey);
    }

    @Bean
    public CpaApiClient cpaApiClient(
            @Value("${cpa.base-url:${CPA_BASE_URL:http://127.0.0.1:8317}}") String baseUrl,
            @Value("${cpa.timeout-ms:${CPA_TIMEOUT_MS:5000}}") long timeoutMs,
            @Value("${cpa.management-key:${CPA_MANAGEMENT_KEY:}}") String managementKey
    ) {
        return new CpaApiClient(baseUrl, Duration.ofMillis(timeoutMs), managementKey);
    }

    @Bean
    public CpaApiKeyManager cpaApiKeyManager(
            CpaApiClient cpaApiClient,
            @Value("${cpa.timeout-ms:${CPA_TIMEOUT_MS:5000}}") long timeoutMs
    ) {
        return new CpaApiKeyManager(cpaApiClient, Duration.ofMillis(timeoutMs));
    }
}
