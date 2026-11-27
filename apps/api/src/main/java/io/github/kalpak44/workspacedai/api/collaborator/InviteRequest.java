package io.github.kalpak44.workspacedai.api.collaborator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Whom to invite into a workspace")
public record InviteRequest(
    @Schema(example = "ana@example.com", maxLength = 254) @NotBlank @Email @Size(max = 254)
        String email) {}
