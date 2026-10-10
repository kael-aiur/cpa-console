package site.kael.cpa.console.admin.credential.exception;

import site.kael.cpa.console.admin.credential.dto.AdminCredentialResetResponse;

public class CredentialResetException extends RuntimeException {
    private final AdminCredentialResetResponse response;

    public CredentialResetException(AdminCredentialResetResponse response) {
        super(response.message());
        this.response = response;
    }

    public AdminCredentialResetResponse response() {
        return response;
    }
}
