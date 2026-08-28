package site.kael.cpa.console.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import site.kael.cpa.console.auth.security.ApiKeyAuthenticationProvider;

@Configuration
public class SecurityConfig {
    @Bean
    public AuthenticationManager authenticationManager(ApiKeyAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                .securityContext(context -> context.requireExplicitSave(false))
                .logout(logout -> logout.disable())
                .requestCache(requestCache -> requestCache.disable());
        return http.build();
    }
}
