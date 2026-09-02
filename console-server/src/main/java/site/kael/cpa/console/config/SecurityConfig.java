package site.kael.cpa.console.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import site.kael.cpa.console.auth.security.ApiKeyAuthenticationProvider;
import site.kael.cpa.console.auth.security.PersistentLoginTokenFilter;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.user.manager.UserManager;

@Configuration
public class SecurityConfig {
    @Bean
    public AuthenticationManager authenticationManager(ApiKeyAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PersistentLoginTokenManager persistentLoginTokenManager,
            UserManager userManager,
            SecurityContextRepository securityContextRepository
    ) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/login"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/csrf", "/api/login", "/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(false))
                .logout(logout -> logout.disable())
                .requestCache(requestCache -> requestCache.disable())
                .addFilterBefore(
                        new PersistentLoginTokenFilter(persistentLoginTokenManager, userManager, securityContextRepository),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
