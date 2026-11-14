package io.github.kalpak44.workspacedai.api.user;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 72 is the most BCrypt reads; a longer password would be silently cut there. */
@Schema(description = "A user to create")
public record CreateUserRequest(
    @Schema(example = "ada", maxLength = 100) @NotBlank @Size(max = 100) String username,
    @Schema(example = "a-longer-passphrase", minLength = 8, maxLength = 72)
        @NotBlank
        @Size(min = 8, max = 72)
        String password,
    @Schema(example = "Ada", maxLength = 100) @NotNull @Size(max = 100) String firstName,
    @Schema(example = "Lovelace", maxLength = 100) @NotNull @Size(max = 100) String lastName,
    @Schema(description = "USER when left out; an ADMIN also manages users", example = "USER")
        Role role) {

  String trimmedUsername() {
    return username.strip();
  }

  String trimmedFirstName() {
    return firstName.strip();
  }

  String trimmedLastName() {
    return lastName.strip();
  }

  Role roleOrUser() {
    return role == null ? Role.USER : role;
  }
}
