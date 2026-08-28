package site.kael.cpa.console.admin.credential.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialListResponse;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialResponse;
import site.kael.cpa.console.core.credential.manager.CredentialManager;

import java.util.List;

@Service
public class AdminCredentialService {
    private final CredentialManager credentialManager;

    public AdminCredentialService(CredentialManager credentialManager) {
        this.credentialManager = credentialManager;
    }

    public AdminCredentialListResponse list() {
        List<site.kael.cpa.console.core.credential.model.Credential> credentials = credentialManager.synchronizeAndFindAll();
        return new AdminCredentialListResponse(credentials.stream().map(AdminCredentialResponse::from).toList(), credentials.size());
    }

    public AdminCredentialResponse updateTags(long id, List<String> tags) {
        List<String> normalized = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return AdminCredentialResponse.from(credentialManager.updateTags(id, normalized));
    }
}
