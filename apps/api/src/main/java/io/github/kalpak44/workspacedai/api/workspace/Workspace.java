package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A workspace AI agents run in")
public record Workspace(
    @Schema(
            description = "Assigned by the server",
            example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
        UUID id,
    @Schema(description = "Display name", example = "Research", maxLength = 100) String name,
    @Schema(description = "The model provider it was created with", example = "openrouter")
        String modelProvider,
    @Schema(description = "The size of its VM, one of /vm-sizes; null for none", example = "cx23")
        String serverType,
    @Schema(
            description = "The model its agent replies with; null for the harness's default",
            example = "anthropic/claude-sonnet-5.5")
        String model,
    @Schema(description = "Whether its runtime can be used yet", example = "INITIALIZING")
        WorkspaceStatus status,
    @Schema(
            description =
                "Whether secrets changed since its runtime started, and apply only after a restart",
            example = "false")
        boolean restartRequired) {

  static Workspace of(WorkspaceEntity entity, WorkspaceStatus status) {
    return new Workspace(
        entity.getId(),
        entity.getName(),
        entity.getModelProvider(),
        entity.getServerType(),
        entity.getModel(),
        status,
        entity.isSecretsPendingRestart());
  }
}
