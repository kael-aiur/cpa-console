package site.kael.cpa.console.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Service;
import site.kael.cpa.console.auth.security.PersistentLoginTokenFilter;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;

@Service
public class LogoutService {
    private final SecurityContextLogoutHandler securityContextLogoutHandler = new SecurityContextLogoutHandler();
    private final PersistentLoginTokenManager persistentLoginTokenManager;

    public LogoutService(PersistentLoginTokenManager persistentLoginTokenManager) {
        this.persistentLoginTokenManager = persistentLoginTokenManager;
    }

    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        persistentLoginTokenManager.revoke(findPersistentCookieValue(request));
        securityContextLogoutHandler.logout(request, response, authentication);
        SecurityContextHolder.clearContext();

        expireCookie(response, PersistentLoginTokenFilter.COOKIE_NAME);
        expireCookie(response, "JSESSIONID");
    }

    private String findPersistentCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (PersistentLoginTokenFilter.COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private void expireCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
