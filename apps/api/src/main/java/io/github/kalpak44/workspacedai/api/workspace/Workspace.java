package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
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
    @Schema(
            description = "The IANA time zone its agent reads times in, as USER_TIMEZONE",
            example = "Europe/Sofia")
        String timeZone,
    @Schema(description = "Whether its runtime can be used yet", example = "INITIALIZING")
        WorkspaceStatus status,
    @Schema(
            description =
                "Whether secrets changed since its runtime started, and apply only after a restart",
            example = "false")
        boolean restartRequired,
    @Schema(description = "The account's free demo, which is never charged", example = "false")
        boolean demo,
    @Schema(
            description =
                "When a demo is deleted; null for every other workspace, and for a demo"
                    + " whose VM has not started yet",
            example = "2026-11-19T18:00:00Z")
        Instant expiresAt,
    @Schema(
            description =
                "The user who created it, and pays for it; everyone else on it is a collaborator",
            example = "8d0f5a4e-2b7c-4f1e-9c3a-6e5d4c3b2a10")
        UUID ownerId) {

  static Workspace of(WorkspaceEntity entity, WorkspaceStatus status) {
    return new Workspace(
        entity.getId(),
        entity.getName(),
        entity.getModelProvider(),
        entity.getServerType(),
        entity.getModel(),
        entity.getTimeZone(),
        status,
        entity.isSecretsPendingRestart(),
        entity.isDemo(),
        entity.getExpiresAt(),
        entity.getOwnerId());
  }
}
