package site.kael.cpa.console.usage.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.auth.security.ConsolePrincipal;
import site.kael.cpa.console.usage.dto.UsageRecordsResponse;
import site.kael.cpa.console.usage.dto.UsageSummaryResponse;
import site.kael.cpa.console.usage.service.UsageService;

import java.time.Instant;

@RestController
@RequestMapping("/api/usage")
public class UsageController {
    private final UsageService usageService;
    public UsageController(UsageService usageService) { this.usageService = usageService; }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(Authentication authentication, @RequestParam(name = "start") String start, @RequestParam(name = "end") String end) {
        return UsageSummaryResponse.from(usageService.summary(hash(authentication), parse(start), parse(end)));
    }

    @GetMapping("/records")
    public UsageRecordsResponse records(Authentication authentication, @RequestParam(name = "start") String start, @RequestParam(name = "end") String end,
                                        @RequestParam(name = "page", defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "10") int pageSize) {
        return UsageRecordsResponse.from(usageService.records(hash(authentication), parse(start), parse(end), page, pageSize));
    }

    private String hash(Authentication authentication) { return ((ConsolePrincipal) authentication.getPrincipal()).user().apiKeyHash(); }
    private Instant parse(String value) {
        try { return Instant.parse(value); } catch (Exception exception) { throw new IllegalArgumentException("时间格式必须为 ISO-8601"); }
    }
}
