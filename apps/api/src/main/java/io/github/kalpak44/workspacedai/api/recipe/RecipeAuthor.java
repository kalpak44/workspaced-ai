package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The name is read from Auth0 as recipes are shown, and is Unknown when Auth0 has none. No email or
 * picture: every user sees the author, and those are not theirs to share.
 */
@Schema(description = "Who wrote a recipe")
public record RecipeAuthor(
    @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
    @Schema(example = "Ada") String firstName,
    @Schema(example = "Lovelace") String lastName) {}
