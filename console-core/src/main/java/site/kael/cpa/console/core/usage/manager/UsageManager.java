package site.kael.cpa.console.core.usage.manager;

import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.usage.dao.UsageQueryDao;
import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import site.kael.cpa.console.core.usage.model.UsageSummary;

import java.time.Instant;

@Component
public class UsageManager {
    private final UsageQueryDao usageQueryDao;
    public UsageManager(UsageQueryDao usageQueryDao) { this.usageQueryDao = usageQueryDao; }
    public UsageSummary summary(String apiKeyHash, Instant start, Instant end) { return usageQueryDao.summary(apiKeyHash, start, end); }
    public UsageRecordsPage records(String apiKeyHash, Instant start, Instant end, int page, int pageSize) { return usageQueryDao.records(apiKeyHash, start, end, page, pageSize); }
}
