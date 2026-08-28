package site.kael.cpa.console.admin.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.admin.dto.AdminUserListResponse;
import site.kael.cpa.console.admin.dto.AdminUserRequest;
import site.kael.cpa.console.admin.dto.AdminUserResponse;
import site.kael.cpa.console.admin.dto.ApiKeyResponse;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.core.user.model.UserRole;

@Service
public class AdminUserService {
    private final UserManager userManager;

    public AdminUserService(UserManager userManager) {
        this.userManager = userManager;
    }

    public AdminUserListResponse list() {
        var users = userManager.findAll();
        return new AdminUserListResponse(users.stream()
                .map(user -> AdminUserResponse.from(user, userManager.apiKey(user)))
                .toList(), users.size());
    }

    public AdminUserResponse get(long id) {
        User user = userManager.findById(id);
        return AdminUserResponse.from(user, userManager.apiKey(user));
    }

    public AdminUserResponse create(AdminUserRequest request) {
        requireRequest(request);
        User user = userManager.create(request.nickname(), parseRole(request.role()));
        return AdminUserResponse.from(user, userManager.apiKey(user));
    }

    public AdminUserResponse update(long id, AdminUserRequest request) {
        requireRequest(request);
        User user = userManager.update(id, request.nickname(), parseRole(request.role()));
        return AdminUserResponse.from(user, userManager.apiKey(user));
    }

    public ApiKeyResponse apiKey(long id) {
        User user = userManager.findById(id);
        return new ApiKeyResponse(userManager.apiKey(user));
    }

    public void delete(long id) {
        userManager.delete(id);
    }

    private void requireRequest(AdminUserRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
    }

    private UserRole parseRole(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Role is required");
        }
        try {
            return UserRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Role must be either user or admin");
        }
    }
}
