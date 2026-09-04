package site.kael.cpa.console.admin.model.dto;
public record AdminLiteLlmSyncConfigRequest(String url, boolean proxy_enabled, String proxy_host, int proxy_port) {}
