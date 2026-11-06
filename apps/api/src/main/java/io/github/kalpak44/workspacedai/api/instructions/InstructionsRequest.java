package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bounded so the agent's context is not filled by instructions alone; empty clears them. */
@Schema(description = "Instructions to replace the current ones with")
public record InstructionsRequest(
    @Schema(description = "Markdown", example = "Use pnpm.", maxLength = 100_000)
        @NotNull
        @Size(max = 100_000)
        String instructions) {}
