package site.kael.cpa.console.usage.dto;

import site.kael.cpa.console.core.usage.model.UsageRecordsPage;
import java.util.List;

public record UsageRecordsResponse(List<UsageRecordResponse> records, int page, int page_size, long total, int total_pages) {
    public static UsageRecordsResponse from(UsageRecordsPage value) {
        return new UsageRecordsResponse(value.records().stream().map(UsageRecordResponse::from).toList(), value.page(), value.pageSize(), value.total(), value.totalPages());
    }
}
