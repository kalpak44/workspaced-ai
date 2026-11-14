package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A recipe as the store lists it, without its Markdown")
public record RecipeSummary(
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
    @Schema(description = "Whether the caller wrote it") boolean mine) {}
