package site.kael.cpa.console.usage.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.core.usage.manager.UsageManager;
import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import site.kael.cpa.console.core.usage.model.UsageSummary;

import java.time.Instant;

@Service
public class UsageService {
    private final UsageManager usageManager;
    public UsageService(UsageManager usageManager) { this.usageManager = usageManager; }
    public UsageSummary summary(String hash, Instant start, Instant end) { return usageManager.summary(hash, start, end); }
    public UsageRecordsPage records(String hash, Instant start, Instant end, int page, int pageSize) { return usageManager.records(hash, start, end, page, pageSize); }
}
