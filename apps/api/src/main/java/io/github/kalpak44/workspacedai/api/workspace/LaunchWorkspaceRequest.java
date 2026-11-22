package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "The provider key a draft workspace is launched with")
public record LaunchWorkspaceRequest(
    @Schema(
            description =
                "Checked with the provider before the VM is created, then kept as the "
                    + "workspace's provider secret",
            example = "sk-or-v1-…",
            maxLength = 1000)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        @Size(max = 1000)
        String apiKey,
    UUID connectorId) {

  /** A key pasted with a trailing newline is the same key. */
  String trimmedApiKey() {
    return apiKey == null ? null : apiKey.strip();
  }
}
