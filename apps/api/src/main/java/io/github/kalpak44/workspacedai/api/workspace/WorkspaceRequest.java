package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of both create and rename. 100 matches the column, so a long name is a 400. */
@Schema(description = "The name to create a workspace with, or to rename it to")
public record WorkspaceRequest(
    @Schema(example = "Research", maxLength = 100) @NotBlank @Size(max = 100) String name) {

  /** Surrounding whitespace is never part of a name, so "Docs" and " Docs " are one. */
  String trimmedName() {
    return name.strip();
  }
}
