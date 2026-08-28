package site.kael.cpa.console.auth.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {
    private final String apiKey;

    public ApiKeyAuthenticationToken(String apiKey) {
        super(null);
        this.apiKey = apiKey;
        setAuthenticated(false);
    }

    public String apiKey() {
        return apiKey;
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return apiKey;
    }
}
