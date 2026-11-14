package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A recipe's rating once the caller's own has been saved")
public record RatingResult(
    @Schema(example = "4.5") double average,
    @Schema(example = "12") int count,
    @Schema(description = "The stars the caller gave", example = "5") Integer myRating) {}
