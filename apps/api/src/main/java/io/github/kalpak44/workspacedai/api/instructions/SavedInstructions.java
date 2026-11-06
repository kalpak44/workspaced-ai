package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Instructions as saved")
public record SavedInstructions(
    @Schema(description = "As Markdown", example = "Use pnpm.") String instructions,
    @Schema(
            description =
                "Whether the running workspace already has them; if not, it gets them when it"
                    + " next starts")
        boolean applied) {}
