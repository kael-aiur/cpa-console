package site.kael.cpa.console.monitor.dto;

/** D7/T11 identity-free retention-loss interval with its precision flag. */
public record CoverageLossResponse(long start, long end, boolean conservative) {
}
