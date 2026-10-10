package site.kael.cpa.console.admin.credential.dto;

import site.kael.cpa.console.core.credential.model.Credential;

import java.time.Instant;
import java.util.List;

public record AdminCredentialResponse(Long id, String name, String credential_type, String reference_id, String provider,
                                      boolean enabled, List<String> tags, Instant created_at, Instant updated_at) {
    public static AdminCredentialResponse from(Credential credential, String provider) {
        return new AdminCredentialResponse(credential.id(), credential.name(), credential.type(),
                credential.referenceId(), provider, credential.enabled(), credential.tags(), credential.createdAt(), credential.updatedAt());
    }
}
