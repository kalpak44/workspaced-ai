package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a workspace's agent is told, besides its owner's general instructions")
public record WorkspaceInstructions(
    @Schema(description = "The workspace's own instructions, as Markdown", example = "Use pnpm.")
        String instructions,
    @Schema(
            description =
                "The harness image's guide to the workspace, which the agent is given first and"
                    + " nobody can edit; null while the workspace is not running")
        String guide) {}
