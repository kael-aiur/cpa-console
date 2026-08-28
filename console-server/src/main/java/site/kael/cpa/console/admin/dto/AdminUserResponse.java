package site.kael.cpa.console.admin.dto;

import site.kael.cpa.console.core.user.model.User;

import java.time.Instant;

public record AdminUserResponse(Long user_id, String nickname, String role, String api_key, Instant created_at) {
    public static AdminUserResponse from(User user, String plaintextApiKey) {
        return new AdminUserResponse(
                user.id(),
                user.nickname(),
                user.role().value(),
                maskApiKey(plaintextApiKey),
                user.createdAt()
        );
    }

    private static String maskApiKey(String apiKey) {
        // The plaintext key is intentionally never included in list/detail responses.
        // It is returned only by the explicitly authorized copy endpoint.
        if (apiKey == null || apiKey.length() <= 12) {
            return "••••••••••••";
        }
        return apiKey.substring(0, Math.min(8, apiKey.length()))
                + "••••••••••••"
                + apiKey.substring(Math.max(0, apiKey.length() - 4));
    }
}
