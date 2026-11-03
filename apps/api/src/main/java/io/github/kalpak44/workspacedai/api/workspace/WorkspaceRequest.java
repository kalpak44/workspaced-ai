package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of both create and rename. 100 matches the column, so a long name is a 400. */
public record WorkspaceRequest(@NotBlank @Size(max = 100) String name) {

  /** Surrounding whitespace is never part of a name, so "Docs" and " Docs " are one. */
  String trimmedName() {
    return name.strip();
  }
}
