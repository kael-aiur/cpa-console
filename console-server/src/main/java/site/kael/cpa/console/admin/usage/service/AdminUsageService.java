package site.kael.cpa.console.admin.usage.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.admin.dto.AdminUsageUserListResponse;
import site.kael.cpa.console.admin.dto.AdminUsageUserResponse;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.core.usage.manager.UsageManager;
import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import site.kael.cpa.console.core.usage.model.UsageSummary;

import java.time.Instant;

@Service
public class AdminUsageService {
    private final UsageManager usageManager;
    private final UserManager userManager;

    public AdminUsageService(UsageManager usageManager, UserManager userManager) {
        this.usageManager = usageManager;
        this.userManager = userManager;
    }

    public UsageSummary summary(Long userId, Instant start, Instant end) {
        return usageManager.summary(userHash(userId), start, end);
    }

    public UsageRecordsPage records(Long userId, Instant start, Instant end, int page, int pageSize) {
        return usageManager.records(userHash(userId), start, end, page, pageSize);
    }

    public AdminUsageUserListResponse users() {
        return new AdminUsageUserListResponse(userManager.findAll().stream()
                .map(user -> new AdminUsageUserResponse(user.id(), user.nickname(), user.role().value()))
                .toList());
    }

    private String userHash(Long userId) {
        if (userId == null) return null;
        User user = userManager.findById(userId);
        return user.apiKeyHash();
    }
}
