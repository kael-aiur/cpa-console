package site.kael.cpa.console.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Service;

@Service
public class LogoutService {
    private final SecurityContextLogoutHandler securityContextLogoutHandler = new SecurityContextLogoutHandler();

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        securityContextLogoutHandler.logout(request, response, null);
        SecurityContextHolder.clearContext();

        Cookie sessionCookie = new Cookie("JSESSIONID", "");
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge(0);
        sessionCookie.setHttpOnly(true);
        response.addCookie(sessionCookie);
    }
}
