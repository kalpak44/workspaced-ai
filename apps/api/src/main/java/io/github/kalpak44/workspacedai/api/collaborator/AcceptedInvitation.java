package io.github.kalpak44.workspacedai.api.collaborator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The workspace an accepted invitation opened")
public record AcceptedInvitation(UUID workspaceId) {}
