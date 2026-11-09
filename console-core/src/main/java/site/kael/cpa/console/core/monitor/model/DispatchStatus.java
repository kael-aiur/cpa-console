package site.kael.cpa.console.core.monitor.model;

/** Whether a run may have produced a real upstream verification POST (D5.1). */
public enum DispatchStatus {
    /** Proven or conservatively recorded as never sent. */
    NOT_SENT,
    /** Send fence committed; may or may not have reached upstream. */
    MAY_HAVE_SENT
}
