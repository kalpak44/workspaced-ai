package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 72 is the most BCrypt reads; a longer password would be silently cut there. */
@Schema(description = "A password change; the current password proves it is the user asking")
public record PasswordRequest(
    @Schema(example = "admin", maxLength = 200) @NotBlank @Size(max = 200) String currentPassword,
    @Schema(example = "a-longer-passphrase", minLength = 8, maxLength = 72)
        @NotBlank
        @Size(min = 8, max = 72)
        String newPassword) {}
