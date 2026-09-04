package site.kael.cpa.console.admin.model.dto;
import site.kael.cpa.console.core.model.model.LiteLlmSyncConfig;
public record AdminLiteLlmSyncConfigResponse(String url, boolean proxy_enabled, String proxy_host, int proxy_port, java.time.Instant updated_at) {
 public static AdminLiteLlmSyncConfigResponse from(LiteLlmSyncConfig v) { return new AdminLiteLlmSyncConfigResponse(v.url(),v.proxyEnabled(),v.proxyHost(),v.proxyPort(),v.updatedAt()); }
}
