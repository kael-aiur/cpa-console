package site.kael.cpa.console.core.usage.model;

import com.fasterxml.jackson.databind.JsonNode;

public record UsageEventParseResult(UsageEvent event, JsonNode rawPayload) {}
