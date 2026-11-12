package io.github.kalpak44.workspacedai.api.backup;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A new workspace made from a backup")
public record CloneBackupRequest(
    @Schema(example = "Research copy", maxLength = 100) @NotBlank @Size(max = 100) String name,
    @Schema(
            description =
                "The provider's key. Leave it out to reuse the key of the workspace the backup was"
                    + " made of, which works only while that workspace exists.",
            maxLength = 1000)
        @Size(max = 1000)
        String apiKey) {

  String trimmedName() {
    return name.strip();
  }

  /** Null when none was given. */
  String trimmedApiKey() {
    return apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
  }
}
