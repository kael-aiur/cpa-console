package site.kael.cpa.console.admin.usage.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.admin.dto.AdminUsageUserListResponse;
import site.kael.cpa.console.admin.usage.service.AdminUsageService;
import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import site.kael.cpa.console.core.usage.model.UsageSummary;
import site.kael.cpa.console.usage.dto.UsageRecordsResponse;
import site.kael.cpa.console.usage.dto.UsageSummaryResponse;

import java.time.Instant;

@RestController
@RequestMapping("/admin/usage")
public class AdminUsageController {
    private final AdminUsageService service;
    public AdminUsageController(AdminUsageService service) { this.service = service; }

    @GetMapping("/users")
    public AdminUsageUserListResponse users() { return service.users(); }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(Authentication authentication,
                                        @RequestParam(name = "start") String start,
                                        @RequestParam(name = "end") String end,
                                        @RequestParam(name = "user_id", required = false) Long userId) {
        requireAdmin(authentication);
        UsageSummary value = service.summary(userId, parse(start), parse(end));
        return UsageSummaryResponse.from(value);
    }

    @GetMapping("/records")
    public UsageRecordsResponse records(Authentication authentication,
                                        @RequestParam(name = "start") String start,
                                        @RequestParam(name = "end") String end,
                                        @RequestParam(name = "user_id", required = false) Long userId,
                                        @RequestParam(name = "page", defaultValue = "1") int page,
                                        @RequestParam(name = "page_size", defaultValue = "10") int pageSize) {
        requireAdmin(authentication);
        UsageRecordsPage value = service.records(userId, parse(start), parse(end), page, pageSize);
        return UsageRecordsResponse.from(value);
    }

    private void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("没有权限执行此操作");
        }
    }

    private Instant parse(String value) {
        try { return Instant.parse(value); } catch (Exception exception) { throw new IllegalArgumentException("时间格式必须为 ISO-8601"); }
    }
}
