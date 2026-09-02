package site.kael.cpa.console.model.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.auth.security.ConsolePrincipal;
import site.kael.cpa.console.model.dto.CodexModelCatalogResponse;
import site.kael.cpa.console.model.service.AvailableModelService;

@RestController
@RequestMapping("/api/codex")
public class CodexModelCatalogController {
    private final AvailableModelService availableModelService;

    public CodexModelCatalogController(AvailableModelService availableModelService) {
        this.availableModelService = availableModelService;
    }

    @GetMapping("/model_catalog")
    public CodexModelCatalogResponse modelCatalog(Authentication authentication) {
        return availableModelService.codexModelCatalog(((ConsolePrincipal) authentication.getPrincipal()).user());
    }
}
