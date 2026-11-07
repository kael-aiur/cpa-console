package site.kael.cpa.console.core.monitor.model;

/**
 * Terminal run categories used by queries and retention (D5.1 / D6).
 * SUCCESS, FAILED and UNKNOWN with MAY_HAVE_SENT dispatches form the verification sample;
 * CONFIG_ERROR and every NOT_SENT run stay outside the sample denominator.
 */
public enum RunTerminalType {
    SUCCESS,
    FAILED,
    UNKNOWN,
    CONFIG_ERROR,
    SKIPPED,
    PENDING
}
