package site.kael.cpa.console.admin.monitor.dto;

/**
 * T10/D7 admin error contract: a stable local code plus a fixed local message. Neither the
 * message nor the code ever echoes request parameters, endpoint paths or upstream text.
 */
public record AdminMonitorErrorResponse(String code, String message) {
}
