package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The key is checked with the provider and handed to the workspace, never stored here. */
@Schema(description = "A new workspace: its name, model provider and the key for that provider")
public record CreateWorkspaceRequest(
    @Schema(example = "Research", maxLength = 100) @NotBlank @Size(max = 100) String name,
    @Schema(description = "An id from /model-providers", example = "openrouter") @NotBlank
        String modelProvider,
    @Schema(
            description = "Checked with the provider before the workspace is created",
            example = "sk-or-v1-…",
            maxLength = 1000)
        @NotBlank
        @Size(max = 1000)
        String apiKey) {

  /** Surrounding whitespace is never part of a name, so "Docs" and " Docs " are one. */
  String trimmedName() {
    return name.strip();
  }

  /** A key pasted with a trailing newline is the same key. */
  String trimmedApiKey() {
    return apiKey.strip();
  }
}
