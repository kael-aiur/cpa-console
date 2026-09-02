package site.kael.cpa.console.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.auth.dto.LoginRequest;
import site.kael.cpa.console.auth.dto.LoginResponse;
import site.kael.cpa.console.auth.dto.UserInfoResponse;
import site.kael.cpa.console.auth.security.ApiKeyAuthenticationToken;
import site.kael.cpa.console.auth.security.ConsolePrincipal;
import site.kael.cpa.console.auth.security.PersistentLoginTokenFilter;
import site.kael.cpa.console.auth.service.LogoutService;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.auth.model.NewPersistentLoginToken;
import site.kael.cpa.console.core.user.model.User;

import java.time.Duration;

@RestController
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final LogoutService logoutService;
    private final PersistentLoginTokenManager persistentLoginTokenManager;
    private final SecurityContextRepository securityContextRepository;
    private final Duration persistentLoginTtl;

    public AuthController(
            AuthenticationManager authenticationManager,
            LogoutService logoutService,
            PersistentLoginTokenManager persistentLoginTokenManager,
            SecurityContextRepository securityContextRepository,
            @Value("${console.auth.persistent-login-ttl:${CPA_CONSOLE_LOGIN_TTL:7d}}") Duration persistentLoginTtl
    ) {
        this.authenticationManager = authenticationManager;
        this.logoutService = logoutService;
        this.persistentLoginTokenManager = persistentLoginTokenManager;
        this.securityContextRepository = securityContextRepository;
        this.persistentLoginTtl = persistentLoginTtl;
    }

    @PostMapping("/api/login")
    public LoginResponse login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        Authentication authentication = authenticationManager.authenticate(new ApiKeyAuthenticationToken(request.apiKey()));
        User user = ((ConsolePrincipal) authentication.getPrincipal()).user();
        NewPersistentLoginToken token = persistentLoginTokenManager.create(user.id());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        addPersistentLoginCookie(httpRequest, httpResponse, token);

        return LoginResponse.from(user);
    }

    @GetMapping("/api/user/info")
    public UserInfoResponse userInfo(Authentication authentication) {
        return UserInfoResponse.from(((ConsolePrincipal) authentication.getPrincipal()).user());
    }

    @GetMapping("/api/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping("/api/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        logoutService.logout(request, response, authentication);
    }

    private void addPersistentLoginCookie(HttpServletRequest request, HttpServletResponse response, NewPersistentLoginToken token) {
        Cookie cookie = new Cookie(PersistentLoginTokenFilter.COOKIE_NAME, token.selector() + "." + token.secret());
        cookie.setPath("/");
        cookie.setMaxAge((int) persistentLoginTtl.toSeconds());
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
