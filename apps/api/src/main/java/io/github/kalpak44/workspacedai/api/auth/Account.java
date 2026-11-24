package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The signed-in user's account, its profile read from Auth0")
public record Account(
    @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
    @Schema(example = "ada@example.com") String email,
    @Schema(example = "Ada") String firstName,
    @Schema(example = "Lovelace") String lastName,
    @Schema(
            description =
                "A picture URL: the user's Gravatar, or Auth0's picture of their initials",
            example = "https://s.gravatar.com/avatar/0bc83cb571cd1c50ba6f3e8a78ef1346")
        String avatar,
    @Schema(description = "An admin also manages the platform's mail") Role role,
    @Schema(description = "Whether the account can still start its one free demo workspace")
        boolean demoAvailable,
    @Schema(description = "Where the guided tour is; null while no tour is under way")
        TourProgress tour,
    MailPreferences mail) {

  static Account of(UserEntity user, Auth0Users.Profile profile) {
    return new Account(
        user.getId(),
        profile.email(),
        profile.givenName(),
        profile.familyName(),
        profile.picture().isEmpty() ? null : profile.picture(),
        user.getRole(),
        user.isDemoAvailable(),
        user.getTourProgress(),
        user.getMailPreferences());
  }
}
