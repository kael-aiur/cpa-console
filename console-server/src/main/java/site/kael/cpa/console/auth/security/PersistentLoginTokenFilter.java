package site.kael.cpa.console.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.user.manager.UserManager;

import java.io.IOException;
import java.util.Optional;

/** Restores authentication from a database-backed cookie when the HTTP session is absent. */
public class PersistentLoginTokenFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "CPA_CONSOLE_LOGIN";

    private final PersistentLoginTokenManager tokenManager;
    private final UserManager userManager;
    private final SecurityContextRepository securityContextRepository;

    public PersistentLoginTokenFilter(
            PersistentLoginTokenManager tokenManager,
            UserManager userManager,
            SecurityContextRepository securityContextRepository
    ) {
        this.tokenManager = tokenManager;
        this.userManager = userManager;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        Cookie loginCookie = findCookie(request);
        if (loginCookie != null) {
            Optional<Long> userId = tokenManager.validate(loginCookie.getValue());
            if (userId.isPresent()) {
                ConsolePrincipal principal = new ConsolePrincipal(userManager.findById(userId.get()));
                if (principal.isEnabled()) {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new TokenAuthentication(principal));
                    SecurityContextHolder.setContext(context);
                    securityContextRepository.saveContext(context, request, response);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private Cookie findCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie;
        }
        return null;
    }

    private static final class TokenAuthentication extends AbstractAuthenticationToken {
        private final ConsolePrincipal principal;

        private TokenAuthentication(ConsolePrincipal principal) {
            super(principal.getAuthorities());
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
