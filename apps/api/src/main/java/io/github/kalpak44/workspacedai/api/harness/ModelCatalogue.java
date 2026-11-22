package io.github.kalpak44.workspacedai.api.harness;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The models the workspace's provider offers that can drive an agent")
public record ModelCatalogue(
    @Schema(
            description =
                "What the agent uses when the workspace names no model: the cheapest paid one at"
                    + " the provider's live prices")
        String defaultModel,
    List<ModelOption> models) {}
