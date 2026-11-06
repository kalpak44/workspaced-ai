package io.github.kalpak44.workspacedai.api.workspace;

import java.util.UUID;

/** Published inside the delete's transaction and acted on only once it has committed. */
public record WorkspaceDeleted(UUID id, UUID ownerId) {}
