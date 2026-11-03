package io.github.kalpak44.workspacedai.api.workspace;

/** Where a workspace's runtime stands, as far as the API can tell. */
public enum WorkspaceStatus {
  /** Its pod is scheduled, pulling or starting, and cannot run anything yet. */
  INITIALIZING,
  /** Its pod is running and ready. */
  READY,
  /** Its pod stopped, crashed, or no longer exists. */
  FAILED
}
