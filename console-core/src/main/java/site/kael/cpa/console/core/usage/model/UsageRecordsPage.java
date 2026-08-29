package site.kael.cpa.console.core.usage.model;

import java.util.List;

public record UsageRecordsPage(List<UsageRecord> records, int page, int pageSize, long total, int totalPages) {}
