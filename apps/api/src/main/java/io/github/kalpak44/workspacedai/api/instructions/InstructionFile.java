package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One of a workspace's instruction files, given to its agent in every session")
public record InstructionFile(
    @Schema(description = "The file's name, without .md", example = "working-style") String name,
    @Schema(description = "Markdown", example = "- Keep answers short.") String content) {}
