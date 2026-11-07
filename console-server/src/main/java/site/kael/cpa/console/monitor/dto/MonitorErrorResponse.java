package site.kael.cpa.console.monitor.dto;

/**
 * D7/T11 public error contract: a stable local code and a fixed local message; neither
 * echoes request parameters, endpoint paths, identities or upstream text.
 */
public record MonitorErrorResponse(String code, String message) {
}
