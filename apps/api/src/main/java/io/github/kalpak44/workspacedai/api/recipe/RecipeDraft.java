package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A recipe draft in a workspace, front matter and Markdown, not yet published")
public record RecipeDraft(
    @Schema(description = "The file's name, without .md", example = "wordpress") String name,
    @Schema(
            description = "Front matter, then Markdown",
            example = "---\ntitle: WordPress\n---\n1. …")
        String content) {}
