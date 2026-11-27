package io.github.kalpak44.workspacedai.api.collaborator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The code travels in the body, so it stays out of access logs. */
@Schema(description = "An invitation link's code")
public record AcceptInvitationRequest(
    @Schema(description = "What follows # in the invitation link") @NotBlank @Size(max = 100)
        String code) {}
