package site.kael.cpa.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.kael.cpa.console.core.cpa.client.CpaCredentialVerificationClient;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.monitor.manager.CpaIdentityResolver;

import java.time.Clock;

/**
 * T10 bean wiring for the read-only candidate/model lookup used by the admin monitor API.
 * It builds its own immutable v8 GET client from the same configured entry without
 * touching the T02/T07 config-lock wiring. Candidate identities stay in backend memory
 * and never enter DTOs or logs.
 */
@Configuration
public class AdminMonitorBeans {

    @Bean
    public CpaIdentityResolver monitorCandidateIdentityResolver(
            @Value("${cpa.base-url:${CPA_BASE_URL:http://127.0.0.1:8317}}") String baseUrl,
            @Value("${cpa.console.config-version:${CPA_CONSOLE_CONFIG_VERSION:initial}}") String configVersion,
            @Value("${cpa.management-key:${CPA_MANAGEMENT_KEY:}}") String managementKey,
            ApiKeyCrypto crypto,
            Clock monitorClock) {
        CpaCredentialVerificationClient client = new CpaCredentialVerificationClient(
                new CpaCredentialVerificationClient.CpaEndpoint(baseUrl, configVersion), managementKey);
        return new CpaIdentityResolver(client, crypto, monitorClock);
    }
}
