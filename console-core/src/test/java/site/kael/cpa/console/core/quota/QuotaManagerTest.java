package site.kael.cpa.console.core.quota;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.client.CpaApiClient;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;
import site.kael.cpa.console.core.quota.manager.QuotaManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuotaManagerTest {
    @Test
    void returnsUsageBasedQuotaForCredentialWithoutQuotaProbe() {
        Credential credential = credential("custom-provider", "api_key", "https://example.com");
        CredentialManager credentials = credentialManager(credential);
        CpaApiKeyManager cpa = new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO);

        Map<String, Object> result = new QuotaManager(credentials, cpa).getQuota(credential.referenceId());

        assertEquals("custom-provider", result.get("provider"));
        assertEquals("按量计费", result.get("tierName"));
        assertEquals(List.of(), result.get("windows"));
    }

    @Test
    void returnsUsageBasedQuotaForUnsupportedAuthFileProvider() {
        Credential credential = credential("some-oauth-provider", "auth_file", "");
        CredentialManager credentials = credentialManager(credential);
        CpaApiKeyManager cpa = new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO) {
            @Override
            public Map<String, Object> getAuthFileQuota(String referenceId, String provider, String projectId) {
                throw new AssertionError("unsupported auth-file provider must not invoke quota probe");
            }
        };

        Map<String, Object> result = new QuotaManager(credentials, cpa).getQuota(credential.referenceId());

        assertEquals("some-oauth-provider", result.get("provider"));
        assertEquals("按量计费", result.get("tierName"));
    }

    @Test
    void keepsInteractionsApiKeyDistinctWhenBaseUrlIsMissing() {
        Credential credential = credential("Google Interactions", "apikey", "");
        CredentialManager credentials = credentialManager(credential);
        CpaApiKeyManager cpa = new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO);

        Map<String, Object> result = new QuotaManager(credentials, cpa).getQuota(credential.referenceId());

        assertEquals("google interactions", result.get("provider"));
        assertEquals("按量计费", result.get("tierName"));
    }

    @Test
    void recognizesOpenRouterApiKeyDomainAsUsageBased() {
        Credential credential = credential("unknown", "api_key", "https://openrouter.ai/api/v1");
        CredentialManager credentials = credentialManager(credential);
        CpaApiKeyManager cpa = new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO) {
            @Override
            public Map<String, Object> getZhipuQuota(String referenceId, String baseUrl) {
                throw new AssertionError("OpenRouter must use usage-based quota");
            }
        };

        Map<String, Object> result = new QuotaManager(credentials, cpa).getQuota(credential.referenceId());

        assertEquals("openrouter", result.get("provider"));
        assertEquals("按量计费", result.get("tierName"));
        assertEquals(List.of(), result.get("windows"));
    }

    @Test
    void recognizesZhipuInternationalApiKeyDomain() {
        Credential credential = credential("unknown", "api_key", "https://api.z.ai/v1");
        CredentialManager credentials = credentialManager(credential);
        CpaApiKeyManager cpa = new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO) {
            @Override
            public Map<String, Object> getZhipuQuota(String referenceId, String baseUrl) {
                return Map.of("provider", "zhipu");
            }
        };

        Map<String, Object> result = new QuotaManager(credentials, cpa).getQuota(credential.referenceId());

        assertEquals("zhipu", result.get("provider"));
    }

    @Test
    void keepsMissingCredentialAsBadRequestInput() {
        CredentialManager credentials = new CredentialManager(null, null) {
            @Override
            public Optional<Credential> findByReferenceId(String referenceId) {
                return Optional.empty();
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> new QuotaManager(credentials, new CpaApiKeyManager((CpaApiClient) null, Duration.ZERO))
                        .getQuota("missing"));
    }

    private CredentialManager credentialManager(Credential credential) {
        return new CredentialManager(null, null) {
            @Override
            public Optional<Credential> findByReferenceId(String referenceId) {
                return Optional.of(credential);
            }
        };
    }

    private Credential credential(String provider, String type, String baseUrl) {
        Instant now = Instant.now();
        return new Credential(1L, "credential-1", "测试凭证", type, true, List.of(), provider, baseUrl, "",
                0, 0, List.of(), now, now);
    }
}
