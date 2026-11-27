package io.github.kalpak44.workspacedai.api.collaborator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Someone on a workspace: its owner, a collaborator, or an open invitation")
public record Collaborator(
    @Schema(description = "The user; null for an invitation, which may not have an account yet")
        UUID userId,
    @Schema(description = "The invitation, to revoke it; null for anyone who has joined")
        UUID invitationId,
    @Schema(
            description =
                "Their address, from their account; null for an invitation, whose address is"
                    + " not kept",
            example = "ana@example.com")
        String email,
    @Schema(example = "APPLIED") CollaboratorStatus status,
    @Schema(
            description = "The address of whoever invited them; null for the owner",
            example = "pavel@example.com")
        String invitedBy,
    @Schema(description = "When they joined, or when the invitation was sent; null for the owner")
        Instant since,
    @Schema(description = "When an invitation stops working; null for anyone who has joined")
        Instant expiresAt) {}
