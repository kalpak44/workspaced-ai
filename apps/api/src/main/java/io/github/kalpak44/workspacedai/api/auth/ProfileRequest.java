package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Saved to the user's Auth0 profile; the app keeps no copy. */
@Schema(description = "The names to replace the current ones with")
public record ProfileRequest(
    @Schema(example = "Ada", maxLength = 100) @NotNull @Size(max = 100) String firstName,
    @Schema(example = "Lovelace", maxLength = 100) @NotNull @Size(max = 100) String lastName) {

  String trimmedFirstName() {
    return firstName.strip();
  }

  String trimmedLastName() {
    return lastName.strip();
  }
}
