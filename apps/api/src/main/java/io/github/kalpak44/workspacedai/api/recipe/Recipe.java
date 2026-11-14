package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A recipe: Markdown instructions an agent follows to set something up")
public record Recipe(
    UUID id,
    @Schema(example = "WordPress with a public address") String title,
    @Schema(example = "A WordPress site with MariaDB in Docker, published on its own address")
        String summary,
    @Schema(
            example = "websites",
            allowableValues = {"apps", "websites", "automation", "data", "ai", "devops", "other"})
        String category,
    @Schema(example = "[\"wordpress\", \"docker\"]") List<String> tags,
    Visibility visibility,
    RecipeAuthor author,
    Instant createdAt,
    Instant updatedAt,
    @Schema(description = "When it first turned public; null while it never has")
        Instant publishedAt,
    RecipeRating rating,
    @Schema(description = "The stars the caller gave it; null when they have not rated it")
        Integer myRating,
    @Schema(description = "Whether the caller wrote it") boolean mine,
    @Schema(description = "Markdown", example = "1. Ask the user to add `DB_PASSWORD` as a secret.")
        String content) {

  RecipeSummary toSummary() {
    return new RecipeSummary(
        id,
        title,
        summary,
        category,
        tags,
        visibility,
        author,
        createdAt,
        updatedAt,
        publishedAt,
        rating,
        myRating,
        mine);
  }
}
