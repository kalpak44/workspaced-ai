package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What everyone who rated a recipe gave it")
public record RecipeRating(
    @Schema(
            description = "Mean stars, to one decimal; 0 while nobody has rated it",
            example = "4.5")
        double average,
    @Schema(description = "How many users rated it", example = "12") int count) {

  static RecipeRating of(double average, int count) {
    return new RecipeRating(Math.round(average * 10) / 10.0, count);
  }
}
