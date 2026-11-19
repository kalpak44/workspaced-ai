package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The name the author had when they created the recipe, kept with it; Unknown when they had none.
 * No email or picture: every user sees the author, and those are not theirs to share.
 */
@Schema(description = "Who wrote a recipe")
public record RecipeAuthor(
    @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
    @Schema(example = "Ada") String firstName,
    @Schema(example = "Lovelace") String lastName) {

  static final String UNKNOWN = "Unknown";

  static RecipeAuthor of(UUID id, String firstName, String lastName) {
    return firstName.isBlank() && lastName.isBlank()
        ? new RecipeAuthor(id, UNKNOWN, "")
        : new RecipeAuthor(id, firstName, lastName);
  }
}
