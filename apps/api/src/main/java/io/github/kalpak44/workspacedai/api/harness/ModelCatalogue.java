package io.github.kalpak44.workspacedai.api.harness;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The models the workspace's provider offers that can drive an agent")
public record ModelCatalogue(
    @Schema(description = "What the agent uses when the workspace names no model")
        String defaultModel,
    List<ModelOption> models) {}
