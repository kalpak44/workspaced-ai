package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bounded so the agent's context is not filled by one file alone. */
@Schema(description = "The Markdown to replace an instruction file's with")
public record InstructionsRequest(
    @Schema(example = "- Keep answers short.", maxLength = 100_000) @NotNull @Size(max = 100_000)
        String content) {}
