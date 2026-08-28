package site.kael.cpa.console.auth.service;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import site.kael.cpa.console.core.cpa.exception.InvalidCpaApiKeyException;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;

@Service
public class AuthService {
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MILLIS = 200L;

    private final CpaApiKeyManager cpaApiKeyManager;
    private final UserManager userManager;

    public AuthService(CpaApiKeyManager cpaApiKeyManager, UserManager userManager) {
        this.cpaApiKeyManager = cpaApiKeyManager;
        this.userManager = userManager;
    }

    private void validateWithRetry(String apiKey) {
        for (int retry = 0; ; retry++) {
            try {
                cpaApiKeyManager.validate(apiKey);
                return;
            } catch (InvalidCpaApiKeyException exception) {
                // An invalid user key is deterministic and must never be retried.
                throw exception;
            } catch (RuntimeException exception) {
                if (retry >= MAX_RETRIES || Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MILLIS * (retry + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    public User authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BadCredentialsException("API Key is required");
        }
        validateWithRetry(apiKey.trim());
        User user = userManager.findOrCreateByApiKey(apiKey.trim());
        if (!user.enabled()) {
            throw new BadCredentialsException("User is disabled");
        }
        return user;
    }
}
