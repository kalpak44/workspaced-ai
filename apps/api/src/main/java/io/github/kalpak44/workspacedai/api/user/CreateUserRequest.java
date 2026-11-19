package io.github.kalpak44.workspacedai.api.user;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** 72 is the most BCrypt reads; a longer password would be silently cut there. */
@Schema(description = "A user to create")
public record CreateUserRequest(
    @Schema(example = "ada@example.com", maxLength = 254) @NotBlank @Email @Size(max = 254)
        String email,
    @Schema(example = "a-longer-passphrase", minLength = 8, maxLength = 72)
        @NotBlank
        @Size(min = 8, max = 72)
        String password,
    @Schema(example = "Ada", maxLength = 100) @NotNull @Size(max = 100) String firstName,
    @Schema(example = "Lovelace", maxLength = 100) @NotNull @Size(max = 100) String lastName,
    @Schema(description = "USER when left out; an ADMIN also manages users", example = "USER")
        Role role) {

  /** Lowercased, so one address cannot be registered twice in different capitalizations. */
  String normalizedEmail() {
    return email.strip().toLowerCase(Locale.ROOT);
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
