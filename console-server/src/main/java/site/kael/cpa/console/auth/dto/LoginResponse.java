package site.kael.cpa.console.auth.dto;

import site.kael.cpa.console.core.user.model.User;

public record LoginResponse(UserInfoResponse user) {
    public static LoginResponse from(User user) {
        return new LoginResponse(UserInfoResponse.from(user));
    }
}
