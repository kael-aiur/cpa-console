package site.kael.cpa.console.quota.service;

import org.springframework.stereotype.Service;
import site.kael.cpa.console.core.quota.manager.QuotaManager;

import java.util.List;
import java.util.Map;

@Service
public class QuotaService {
    private final QuotaManager quotaManager;

    public QuotaService(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    public List<Map<String, Object>> listProviders() {
        return quotaManager.listProviders();
    }

    public Map<String, Object> getQuota(String referenceId) {
        return quotaManager.getQuota(referenceId);
    }
}
