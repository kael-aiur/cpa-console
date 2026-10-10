package site.kael.cpa.console.admin.credential.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialListResponse;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialResetResponse;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialResponse;
import site.kael.cpa.console.admin.credential.exception.CredentialResetException;
import site.kael.cpa.console.core.cpa.exception.CpaManagementException;
import site.kael.cpa.console.core.cpa.exception.CpaUnavailableException;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.credential.manager.CredentialManager;
import site.kael.cpa.console.core.credential.model.Credential;
import site.kael.cpa.console.core.quota.manager.QuotaManager;
import site.kael.cpa.console.quota.dto.QuotaInfoResponse;

import java.util.List;

@Service
public class AdminCredentialService {
    private final CredentialManager credentialManager;
    private final QuotaManager quotaManager;
    private final CpaApiKeyManager cpaApiKeyManager;

    public AdminCredentialService(CredentialManager credentialManager, QuotaManager quotaManager, CpaApiKeyManager cpaApiKeyManager) {
        this.credentialManager = credentialManager;
        this.quotaManager = quotaManager;
        this.cpaApiKeyManager = cpaApiKeyManager;
    }

    private AdminCredentialResponse toResponse(Credential credential) {
        return AdminCredentialResponse.from(credential, quotaManager.identifyProvider(credential));
    }

    private Credential requireCredential(long id) {
        return credentialManager.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "凭证不存在"));
    }

    public QuotaInfoResponse getQuota(long id) {
        return new QuotaInfoResponse(quotaManager.getQuota(requireCredential(id).referenceId()));
    }

    public AdminCredentialResetResponse resetQuota(long id) {
        Credential credential = requireCredential(id);
        if (!"auth_file".equals(credential.type()) || !"codex".equals(quotaManager.identifyProvider(credential))) {
            throw new IllegalArgumentException("仅 Codex OAuth 凭证支持重置额度");
        }
        if (credential.referenceId() == null || credential.referenceId().isBlank()) {
            throw new IllegalArgumentException("凭证缺少 CPA auth_index");
        }
        try {
            cpaApiKeyManager.resetCredentialQuota(credential.referenceId());
        } catch (CpaManagementException | CpaUnavailableException exception) {
            boolean unknown = exception instanceof CpaUnavailableException;
            throw new CredentialResetException(new AdminCredentialResetResponse("error",
                    unknown ? "unknown" : "failed", "not_attempted", unknown
                    ? "额度重置结果不确定，请先检查额度，勿直接重复重置"
                    : "额度重置未完成，冷却重置未执行；CPA 可能不支持该账号重置或重置请求被拒绝"));
        }
        try {
            cpaApiKeyManager.resetCredentialCooldown(credential.referenceId());
        } catch (CpaManagementException | CpaUnavailableException exception) {
            throw new CredentialResetException(new AdminCredentialResetResponse("partial_success", "completed",
                    exception instanceof CpaUnavailableException ? "unknown" : "failed",
                    "额度已重置，但冷却重置未确认成功；请勿重复执行额度重置"));
        }
        return new AdminCredentialResetResponse("ok", "completed", "completed", "额度和账号冷却已重置");
    }

    public AdminCredentialListResponse list() {
        List<Credential> credentials = credentialManager.synchronizeAndFindAll();
        return new AdminCredentialListResponse(credentials.stream().map(this::toResponse).toList(), credentials.size());
    }

    public AdminCredentialResponse updateTags(long id, List<String> tags) {
        List<String> normalized = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return toResponse(credentialManager.updateTags(id, normalized));
    }
}
