package site.kael.cpa.console.auth.dto;

import site.kael.cpa.console.core.user.model.User;

public record UserInfoResponse(String nickname, String role, Long user_id) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(user.nickname(), user.role().value(), user.id());
    }
}
