package site.kael.cpa.console.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.admin.dto.AdminUserListResponse;
import site.kael.cpa.console.admin.dto.AdminUserRequest;
import site.kael.cpa.console.admin.dto.AdminUserResponse;
import site.kael.cpa.console.admin.dto.ApiKeyResponse;
import site.kael.cpa.console.admin.service.AdminUserService;

@RestController
@RequestMapping("/admin/users")
public class AdminUserController {
    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public AdminUserListResponse list() {
        return adminUserService.list();
    }

    @GetMapping("/{id}")
    public AdminUserResponse get(@PathVariable("id") long id) {
        return adminUserService.get(id);
    }

    @PostMapping
    public AdminUserResponse create(@RequestBody AdminUserRequest request) {
        return adminUserService.create(request);
    }

    @PutMapping("/{id}")
    public AdminUserResponse update(@PathVariable("id") long id, @RequestBody AdminUserRequest request) {
        return adminUserService.update(id, request);
    }

    @GetMapping("/{id}/api-key")
    public ApiKeyResponse apiKey(@PathVariable("id") long id) {
        return adminUserService.apiKey(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") long id) {
        adminUserService.delete(id);
    }
}
