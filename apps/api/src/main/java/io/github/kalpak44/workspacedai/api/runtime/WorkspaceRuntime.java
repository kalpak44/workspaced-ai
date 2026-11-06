package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Where a workspace actually runs. */
public interface WorkspaceRuntime {

  /** Starts the workspace's runtime with that environment; returns before it is ready. */
  void start(UUID workspaceId, WorkspaceEnvironment environment);

  /**
   * Stops it and returns once it is gone, so a caller can report the workspace deleted only when it
   * really is. Stopping one that is not running is not an error.
   */
  void stop(UUID workspaceId);

  /**
   * Replaces its running pod with a fresh one, keeping its storage, and returns before the new one
   * is ready. Fails when the workspace has nothing that would bring a pod back.
   */
  void restart(UUID workspaceId);

  /** Adds or replaces one secret. A running workspace sees it only once it restarts. */
  void putSecret(UUID workspaceId, String name, String value);

  /** Removes one secret; removing one that is not there is not an error. */
  void removeSecret(UUID workspaceId, String name);

  /** Where the workspace's harness answers; empty while it has none running. */
  Optional<HarnessEndpoint> harness(UUID workspaceId);

  /** One status per id, in a single lookup however many there are. */
  Map<UUID, WorkspaceStatus> statuses(Collection<UUID> workspaceIds);

  /**
   * Reports every status change from now on. It may repeat a status it already reported, so the
   * listener has to tolerate duplicates.
   */
  void watch(BiConsumer<UUID, WorkspaceStatus> listener);
}
