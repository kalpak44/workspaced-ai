package io.github.kalpak44.workspacedai.api.workspace;

import java.util.UUID;

/**
 * One user losing a workspace, whether it was deleted or they were removed from it. Published
 * inside that transaction and acted on only once it has committed.
 */
public record WorkspaceDeleted(UUID id, UUID userId) {}
