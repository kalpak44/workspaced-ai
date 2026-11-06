package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The signed-in user's general instructions, given in all their workspaces")
public record GeneralInstructions(
    @Schema(description = "As Markdown", example = "Answer briefly.") String instructions) {}
