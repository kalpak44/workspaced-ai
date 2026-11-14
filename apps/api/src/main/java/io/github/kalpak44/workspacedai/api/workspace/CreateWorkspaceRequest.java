package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A key, when given, is checked with the provider and handed to the workspace, never stored. */
@Schema(description = "A new workspace: its name, its VM size and its model provider")
public record CreateWorkspaceRequest(
    @Schema(example = "Research", maxLength = 100) @NotBlank @Size(max = 100) String name,
    @Schema(description = "An id from /model-providers", example = "openrouter") @NotBlank
        String modelProvider,
    @Schema(
            description =
                "Optional; checked with the provider before the workspace is created when given",
            example = "sk-or-v1-…",
            maxLength = 1000)
        @Size(max = 1000)
        String apiKey,
    @Schema(description = "One of /vm-sizes; the default size when omitted", example = "cx23")
        @Size(max = 20)
        String serverType) {

  /** Blank means the default size. */
  String serverTypeOrNull() {
    return serverType == null || serverType.isBlank() ? null : serverType.strip();
  }

  /** Surrounding whitespace is never part of a name, so "Docs" and " Docs " are one. */
  String trimmedName() {
    return name.strip();
  }

  /** A key pasted with a trailing newline is the same key; a blank one is none. */
  String trimmedApiKeyOrNull() {
    return apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
  }
}
