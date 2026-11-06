package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The avatar is the image itself as a data: URL. 2 MB of image is about 2.8 million characters of
 * base64, which is where the size limit comes from.
 */
@Schema(description = "The profile to replace the current one with")
public record ProfileRequest(
    @Schema(example = "Ada", maxLength = 100) @NotNull @Size(max = 100) String firstName,
    @Schema(example = "Lovelace", maxLength = 100) @NotNull @Size(max = 100) String lastName,
    @Schema(
            description =
                "A JPG, PNG, WebP or GIF data: URL of at most 2 MB; null or empty removes it",
            example = "data:image/png;base64,iVBORw0KGgo=")
        @Size(max = 2_800_000)
        @Pattern(regexp = "|data:image/(jpeg|png|webp|gif);base64,[A-Za-z0-9+/]+={0,2}")
        String avatar) {

  String trimmedFirstName() {
    return firstName.strip();
  }

  String trimmedLastName() {
    return lastName.strip();
  }

  /** Null rather than empty, so "no avatar" is stored one way. */
  String avatarOrNull() {
    return avatar == null || avatar.isEmpty() ? null : avatar;
  }
}
