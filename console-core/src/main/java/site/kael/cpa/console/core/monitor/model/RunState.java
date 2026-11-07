package site.kael.cpa.console.core.monitor.model;

/** User-visible verification verdict of a monitoring run (D5.1, PRD R05). */
public enum RunState {
    /** Claimed but not yet resolved. */
    PENDING,
    /** Not dispatched (resource conflict, disabled task, plan-key race). */
    SKIPPED,
    /** Target accepted and strictly verified. */
    SUCCESS,
    /** Target rejected the verification attempt. */
    FAILED,
    /** Outcome unknown (transport failure, interrupted, contradictory response). */
    UNKNOWN,
    /** Configuration problem persisted on the task. */
    CONFIG_ERROR
}
