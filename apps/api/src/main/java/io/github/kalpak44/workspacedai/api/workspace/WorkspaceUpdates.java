package io.github.kalpak44.workspacedai.api.workspace;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-statement updates of a workspace row, each in its own transaction, for callers that must
 * not hold one while they ask the VM or the harness.
 */
public interface WorkspaceUpdates {

  /**
   * Takes a draft for launching; 0 when it is not one, or another launch took it first. Committed
   * before the VM is asked for.
   */
  int claimDraft(UUID id);

  /** Hands a draft back after its launch failed, so it can be launched again. */
  int returnToDraft(UUID id);

  /** The database cascades the delete to whatever a draft collected. */
  int deleteDraftsCreatedBefore(Instant before);

  int updateModel(UUID id, String model);

  /** For a caller that has just changed what the harness reads at start. */
  int markRestartRequired(UUID id);

  int updateFirewallAgentAllowed(UUID id, boolean allowed);
}
