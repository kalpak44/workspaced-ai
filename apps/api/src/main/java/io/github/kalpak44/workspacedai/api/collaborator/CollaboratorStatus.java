package io.github.kalpak44.workspacedai.api.collaborator;

/** Where someone on a workspace's list stands. */
public enum CollaboratorStatus {
  /** Created the workspace and pays for it; the only one who can remove anyone. */
  OWNER,
  /** Accepted an invitation, and may do everything but end or pay for the workspace. */
  APPLIED,
  /** Was sent an invitation that has neither been accepted nor expired. */
  PENDING
}
