package site.kael.cpa.console.model.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.kael.cpa.console.model.dto.AvailableModelListResponse;
import site.kael.cpa.console.model.service.AvailableModelService;
import site.kael.cpa.console.auth.security.ConsolePrincipal;

@RestController
@RequestMapping("/api/models")
public class AvailableModelController {
    private final AvailableModelService availableModelService;

    public AvailableModelController(AvailableModelService availableModelService) {
        this.availableModelService = availableModelService;
    }

    @GetMapping
    public AvailableModelListResponse list(Authentication authentication) {
        return availableModelService.list(((ConsolePrincipal) authentication.getPrincipal()).user());
    }
}
