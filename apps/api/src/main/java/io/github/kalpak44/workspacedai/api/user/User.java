package io.github.kalpak44.workspacedai.api.user;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Someone who can sign in")
public record User(
    @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
    @Schema(example = "admin") String username,
    @Schema(example = "Ada") String firstName,
    @Schema(example = "Lovelace") String lastName,
    @Schema(
            description = "The picture as a data: URL; null for the default",
            example = "data:image/png;base64,iVBORw0KGgo=")
        String avatar,
    @Schema(description = "An admin also manages users") Role role) {

  static User of(UserEntity user) {
    return new User(
        user.getId(),
        user.getUsername(),
        user.getFirstName(),
        user.getLastName(),
        user.getAvatar(),
        user.getRole());
  }
}
