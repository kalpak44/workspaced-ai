package io.github.kalpak44.workspacedai.api.session;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
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
            example = "/home/workspace/project")
        String path,
    @Schema(example = "2026-11-03T15:37:24.531Z") Instant createdAt,
    boolean available,
    @Schema(
            description = "The scheduled task this session is a run of; null when a user started it",
            example = "pr-summary")
        String task) {

  Session withTitle(String title) {
    return new Session(id, title, path, createdAt, available, task);
  }

  static Session of(WorkspaceSessionEntity entity) {
    return new Session(
        entity.getId(),
        entity.getTitle(),
        entity.getPath(),
        entity.getCreatedAt(),
        entity.isAvailable(),
        entity.getTask());
  }
}
