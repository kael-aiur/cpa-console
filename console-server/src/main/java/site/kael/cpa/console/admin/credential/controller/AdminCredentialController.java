package site.kael.cpa.console.admin.credential.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialListResponse;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialResponse;
import site.kael.cpa.console.admin.credential.dto.AdminCredentialUpdateRequest;
import site.kael.cpa.console.admin.credential.service.AdminCredentialService;

@RestController
@RequestMapping("/admin/credentials")
public class AdminCredentialController {
    private final AdminCredentialService adminCredentialService;

    public AdminCredentialController(AdminCredentialService adminCredentialService) {
        this.adminCredentialService = adminCredentialService;
    }

    @GetMapping
    public AdminCredentialListResponse list() {
        return adminCredentialService.list();
    }

    @PatchMapping("/{id}")
    public AdminCredentialResponse update(@PathVariable("id") long id, @RequestBody AdminCredentialUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        return adminCredentialService.updateTags(id, request.tags());
    }
}
