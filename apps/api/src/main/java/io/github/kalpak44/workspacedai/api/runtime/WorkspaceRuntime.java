package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.Collection;
import java.util.List;
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

  /**
   * Makes the workspace's web server reachable as that label, the first one of its public host,
   * replacing any it had. It outlives restarts; only {@link #unpublish} or stopping the workspace
   * takes it away.
   */
  void publish(UUID workspaceId, String label);

  /** Takes its web server's name away; one that has none is not an error. */
  void unpublish(UUID workspaceId);

  /** Adds or replaces one secret. A running workspace sees it only once it restarts. */
  void putSecret(UUID workspaceId, String name, String value);

  /** One secret's value; empty when the workspace has no such secret, or none at all. */
  Optional<String> secret(UUID workspaceId, String name);

  /** Removes one secret; removing one that is not there is not an error. */
  void removeSecret(UUID workspaceId, String name);

  /**
   * The last lines its newest pod printed, oldest first; empty when it has no pod or the log cannot
   * be read. Before its harness starts, this is the only way to hear from it.
   */
  List<String> recentLog(UUID workspaceId, int lines);

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
