package site.kael.cpa.console.model.dto;

import site.kael.cpa.console.core.model.model.AvailableModelCredential;

import java.util.List;

public record AvailableModelCredentialResponse(String name, List<String> tags) {
    public static AvailableModelCredentialResponse from(AvailableModelCredential credential) {
        return new AvailableModelCredentialResponse(credential.name(), credential.tags());
    }
}
