package site.kael.cpa.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.kael.cpa.console.core.auth.dao.PersistentLoginTokenDao;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;

import java.time.Duration;

@Configuration
public class AuthCookieConfig {
    @Bean
    public PersistentLoginTokenManager persistentLoginTokenManager(
            PersistentLoginTokenDao dao,
            @Value("${console.auth.persistent-login-ttl:${CPA_CONSOLE_LOGIN_TTL:7d}}") Duration tokenTtl
    ) {
        return new PersistentLoginTokenManager(dao, tokenTtl);
    }
}
