package site.kael.cpa.console.core.model.model;

import java.util.List;

public record AvailableModel(long id, String name, String ownedBy, List<String> tags, String litellmModelId) {
    public AvailableModel(String name, List<String> tags) {
        this(0L, name, "", tags, null);
    }

    public AvailableModel(String name, String ownedBy, List<String> tags, String litellmModelId) {
        this(0L, name, ownedBy, tags, litellmModelId);
    }
}
