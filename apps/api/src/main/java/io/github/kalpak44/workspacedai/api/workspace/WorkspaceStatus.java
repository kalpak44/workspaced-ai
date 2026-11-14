package io.github.kalpak44.workspacedai.api.workspace;

/** Where a workspace's runtime stands, as far as the API can tell. */
public enum WorkspaceStatus {
  /** Its VM is being created, booting or rebooting, and cannot run anything yet. */
  INITIALIZING,
  /** Its VM has reported ready. */
  READY,
  /** It has no VM, or one deleted outside the app. */
  FAILED
}
