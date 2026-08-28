package site.kael.cpa.console.quota.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.quota.dto.QuotaInfoResponse;
import site.kael.cpa.console.quota.dto.QuotaProviderListResponse;
import site.kael.cpa.console.quota.service.QuotaService;

@RestController
@RequestMapping("/api/quota/providers")
public class QuotaController {
    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    public QuotaProviderListResponse listProviders() {
        return new QuotaProviderListResponse(quotaService.listProviders());
    }

    @GetMapping("/{referenceId}/quota")
    public QuotaInfoResponse getQuota(@PathVariable("referenceId") String referenceId) {
        return new QuotaInfoResponse(quotaService.getQuota(referenceId));
    }
}
