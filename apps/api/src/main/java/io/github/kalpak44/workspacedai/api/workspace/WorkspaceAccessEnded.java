package io.github.kalpak44.workspacedai.api.workspace;

import java.util.UUID;

/**
 * One user losing a workspace, raised on every API instance once that has committed, so each closes
 * the terminals and streams it holds open for them. Otherwise a removed collaborator keeps a live
 * shell on the VM for as long as their tab stays open.
 */
public record WorkspaceAccessEnded(UUID workspaceId, UUID userId) {}
