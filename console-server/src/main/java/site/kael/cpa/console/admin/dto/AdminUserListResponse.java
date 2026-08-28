package site.kael.cpa.console.admin.dto;

import java.util.List;

public record AdminUserListResponse(List<AdminUserResponse> users, long total) {
}
