package site.kael.cpa.console.admin.credential.dto;

public record AdminCredentialResetResponse(String status, String quota_reset, String cooldown_reset, String message) {
}
