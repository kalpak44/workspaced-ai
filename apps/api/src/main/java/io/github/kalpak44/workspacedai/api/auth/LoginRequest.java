package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

@Schema(description = "The credentials to sign in with")
public record LoginRequest(
    @Schema(example = "ada@example.com", maxLength = 254) @NotBlank @Size(max = 254) String email,
    @Schema(example = "admin", maxLength = 200) @NotBlank @Size(max = 200) String password) {

  /** Emails are stored lowercased, so any capitalization of one signs in. */
  String normalizedEmail() {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
