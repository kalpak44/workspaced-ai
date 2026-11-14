package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The signed-in user's account")
public record Account(
    @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
    @Schema(example = "admin") String username,
    @Schema(example = "Ada") String firstName,
    @Schema(example = "Lovelace") String lastName,
    @Schema(
            description = "The picture as a data: URL; null for the default",
            example = "data:image/png;base64,iVBORw0KGgo=")
        String avatar,
    @Schema(description = "An admin also manages users") Role role) {

  static Account of(UserEntity user) {
    return new Account(
        user.getId(),
        user.getUsername(),
        user.getFirstName(),
        user.getLastName(),
        user.getAvatar(),
        user.getRole());
  }
}
