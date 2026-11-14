package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "The text to replace a recipe draft's with")
public record RecipeDraftRequest(
    @Schema(example = "---\ntitle: WordPress\n---\n1. …", maxLength = 100_000)
        @NotNull
        @Size(max = 100_000)
        String content) {}
