package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** No avatar: a picture of up to 2 MB per card would make a page of recipes heavy. */
@Schema(description = "Who wrote a recipe")
public record RecipeAuthor(
    @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
    @Schema(example = "admin") String username,
    @Schema(example = "Ada") String firstName,
    @Schema(example = "Lovelace") String lastName) {}
