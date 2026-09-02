package site.kael.cpa.console.model.dto;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.model.model.AvailableModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CodexModelCatalogResponseTest {
    @Test
    void mapsAvailableModelsToCodexCatalogEntries() {
        var response = CodexModelCatalogResponse.from(List.of(
                new AvailableModel("gpt-5", List.of("openai")),
                new AvailableModel("claude-4", List.of("anthropic"))
        ));

        assertEquals(2, response.models().size());
        assertEquals("gpt-5", response.models().get(0).get("slug"));
        assertEquals("claude-4", response.models().get(1).get("display_name"));
    }

    @Test
    void usesRequiredCodexInputAndContextMetadata() {
        Map<String, Object> model = CodexModelCatalogResponse.from(List.of(
                new AvailableModel("custom-model", List.of())
        )).models().getFirst();

        assertEquals(List.of("text", "image"), model.get("input_modalities"));
        assertEquals(1_000_000, model.get("context_window"));
        assertEquals(1_000_000, model.get("max_context_window"));
        assertEquals("medium", model.get("default_reasoning_level"));
        assertInstanceOf(List.class, model.get("supported_reasoning_levels"));
    }

    @Test
    void returnsEmptyCatalogWhenNoModelsAreAvailable() {
        assertEquals(List.of(), CodexModelCatalogResponse.from(List.of()).models());
    }
}
