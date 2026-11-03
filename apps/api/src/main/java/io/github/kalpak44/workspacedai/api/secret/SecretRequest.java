package io.github.kalpak44.workspacedai.api.secret;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The name becomes an environment variable, so it has to be a valid one. The value is kept as sent,
 * whitespace included, since a secret's bytes are not ours to tidy.
 */
@Schema(description = "A secret to add to a workspace")
public record SecretRequest(
    @Schema(example = "GITHUB_TOKEN", maxLength = 100, pattern = SecretRequest.NAME)
        @NotEmpty
        @Size(max = 100)
        @Pattern(regexp = SecretRequest.NAME)
        String name,
    // A Kubernetes Secret holds 1 MiB in all, so one value cannot be allowed to fill it.
    @Schema(example = "ghp_…", maxLength = 65536) @NotEmpty @Size(max = 65536) String value) {

  static final String NAME = "^[A-Za-z_][A-Za-z0-9_]*$";
}
