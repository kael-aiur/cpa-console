package site.kael.cpa.console.auth.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import site.kael.cpa.console.auth.service.AuthService;
import site.kael.cpa.console.core.user.model.User;

@Component
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {
    private final AuthService authService;

    public ApiKeyAuthenticationProvider(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        ApiKeyAuthenticationToken token = (ApiKeyAuthenticationToken) authentication;
        User user = authService.authenticate(token.apiKey());
        ConsolePrincipal principal = new ConsolePrincipal(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
