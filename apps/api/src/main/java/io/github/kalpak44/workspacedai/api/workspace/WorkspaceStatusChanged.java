package io.github.kalpak44.workspacedai.api.workspace;

import java.util.UUID;

/** Published for every status the runtime reports, repeats included. */
public record WorkspaceStatusChanged(UUID id, WorkspaceStatus status) {}
