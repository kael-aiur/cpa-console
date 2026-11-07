package site.kael.cpa.console.monitor.dto;

/**
 * D7/T11 latest record: the result view and the schedule view are separate; an absent
 * record simply means "no retained verification record", never "never verified".
 */
public record LatestInfoResponse(String state, long started_at, Long finished_at) {
}
