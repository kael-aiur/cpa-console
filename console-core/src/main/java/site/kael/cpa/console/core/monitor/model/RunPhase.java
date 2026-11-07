package site.kael.cpa.console.core.monitor.model;

/** Internal execution phase of a monitoring run (D5.1). Never surfaced as a verification verdict. */
public enum RunPhase {
    /** Row inserted for a claimed plan key; not yet dispatched. */
    CLAIMED,
    /** Send fence committed; an HTTP attempt may be in flight. */
    DISPATCHING,
    /** Terminal state persisted. */
    TERMINAL
}
