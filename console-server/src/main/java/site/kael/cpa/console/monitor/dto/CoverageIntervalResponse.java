package site.kael.cpa.console.monitor.dto;

/** D7/T11 half-open coverage interval in epoch milliseconds. */
public record CoverageIntervalResponse(long start, long end) {
}
