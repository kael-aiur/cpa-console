package site.kael.cpa.console.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
import site.kael.cpa.console.auth.service.LogoutService;

@RestController
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final LogoutService logoutService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, LogoutService logoutService) {
        this.authenticationManager = authenticationManager;
        this.logoutService = logoutService;
    }

    @PostMapping("/api/login")
    public LoginResponse login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        Authentication authentication = authenticationManager.authenticate(new ApiKeyAuthenticationToken(request.apiKey()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        return LoginResponse.from(((ConsolePrincipal) authentication.getPrincipal()).user());
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
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        logoutService.logout(request, response);
    }
}
