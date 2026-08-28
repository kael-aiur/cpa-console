package site.kael.cpa.console.quota.dto;

import java.util.List;
import java.util.Map;

public record QuotaProviderListResponse(List<Map<String, Object>> providers) {
}
