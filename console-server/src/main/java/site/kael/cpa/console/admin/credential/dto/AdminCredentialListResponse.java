package site.kael.cpa.console.admin.credential.dto;

import java.util.List;

public record AdminCredentialListResponse(List<AdminCredentialResponse> credentials, long total) {
}
