package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "The provider key a draft workspace is launched with")
public record LaunchWorkspaceRequest(
    @Schema(
            description =
                "Checked with the provider before the VM is created, then kept as the "
                    + "workspace's provider secret",
            example = "sk-or-v1-…",
            maxLength = 1000)
        @NotBlank
        @Size(max = 1000)
        String apiKey) {

  /** A key pasted with a trailing newline is the same key. */
  String trimmedApiKey() {
    return apiKey.strip();
  }
}
