package io.github.kalpak44.workspacedai.api.session;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A chat session in a workspace, with its own working directory")
public record Session(
    @Schema(example = "8b0e4f7c-1d2a-4c3b-9e5f-6a7b8c9d0e1f") UUID id,
    @Schema(
            description = "Null until the agent names it after the first exchange",
            example = "Fix the login form")
        String title,
    @Schema(
            description = "The directory the agent works in, inside the workspace",
            example = "/home/workspace/sessions/8b0e4f7c-1d2a-4c3b-9e5f-6a7b8c9d0e1f")
        String path) {

  static Session of(WorkspaceSessionEntity entity) {
    return new Session(entity.getId(), entity.getTitle(), entity.getPath());
  }
}
