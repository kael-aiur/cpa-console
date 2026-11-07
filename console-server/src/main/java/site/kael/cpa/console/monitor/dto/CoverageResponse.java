package site.kael.cpa.console.monitor.dto;

import java.util.List;

/**
 * D7/T11 coverage facts (D6.2): requested window, as_of, collection start, retention
 * revision, complete/loss/retained/gap intervals and a completeness flag. Never presents a
 * min/max envelope over a hole and never implies real-time health from retention exceptions.
 */
public record CoverageResponse(
        long requested_start,
        long requested_end,
        long as_of,
        long collection_started_at,
        long retention_revision,
        List<CoverageIntervalResponse> complete_intervals,
        List<CoverageLossResponse> loss_intervals,
        List<CoverageIntervalResponse> retained_sample_intervals,
        List<CoverageIntervalResponse> observation_gaps,
        boolean complete) {
}
