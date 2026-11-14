package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The caller's rating of a recipe")
public record RatingRequest(
    @Schema(example = "5", minimum = "1", maximum = "5") @NotNull @Min(1) @Max(5) Integer stars) {}
