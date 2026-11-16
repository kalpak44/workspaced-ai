package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The key is checked with the provider and handed to the workspace's VM, never stored here. */
@Schema(description = "A new workspace: its name, its VM size and its model provider")
public record CreateWorkspaceRequest(
    @Schema(example = "Research", maxLength = 100) @NotBlank @Size(max = 100) String name,
    @Schema(description = "An id from /model-providers", example = "openrouter") @NotBlank
        String modelProvider,
    @Schema(
            description =
                "Checked with the provider before the workspace is created, then kept as its "
                    + "provider secret, which cannot be removed",
            example = "sk-or-v1-…",
            maxLength = 1000)
        @NotBlank
        @Size(max = 1000)
        String apiKey,
    @Schema(description = "One of /vm-sizes; the default size when omitted", example = "cx23")
        @Size(max = 20)
        String serverType,
    @Schema(
            description =
                "An IANA time zone for its agent, usually the browser's; UTC when omitted",
            example = "Europe/Sofia")
        @Size(max = 64)
        String timeZone) {

  /** Blank means the default size. */
  String serverTypeOrNull() {
    return serverType == null || serverType.isBlank() ? null : serverType.strip();
  }

  /** Blank means UTC. */
  String timeZoneOrUtc() {
    return timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.strip();
  }

  /** Surrounding whitespace is never part of a name, so "Docs" and " Docs " are one. */
  String trimmedName() {
    return name.strip();
  }

  /** A key pasted with a trailing newline is the same key. */
  String trimmedApiKey() {
    return apiKey.strip();
  }
}
