package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.compute.ComputeProvider;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Where a workspace actually runs: a Hetzner VM of its own. */
public interface WorkspaceRuntime {

  /**
   * Starts the workspace's runtime with that environment; returns before it is ready.
   *
   * @param provider the cloud it runs in
   * @param serverType the size to start it as; null for the runtime's default
   * @return the size it was started as; null where sizes mean nothing
   */
  String start(
      UUID workspaceId,
      UUID ownerId,
      ComputeProvider provider,
      String serverType,
      WorkspaceEnvironment environment);

  /**
   * Stops it and returns once it is gone, so a caller can report the workspace deleted only when it
   * really is. Stopping one that is not running is not an error.
   */
  void stop(UUID workspaceId);

  /**
   * Shuts it down, keeping its storage, until {@link #resume}; pausing a paused one does nothing.
   * Fails with {@link IllegalStateException} when the workspace has nothing to pause.
   */
  void pause(UUID workspaceId);

  /** Starts a paused one again; resuming one that is not paused does nothing. */
  void resume(UUID workspaceId);

  /**
   * Reboots it, keeping its storage, and returns before it is ready again. Fails with {@link
   * IllegalStateException} when the workspace has nothing to reboot.
   */
  void restart(UUID workspaceId);

  /**
   * Adds one secret; a VM refuses one it already has rather than replacing it. A running workspace
   * sees it only once it restarts.
   */
  void putSecret(UUID workspaceId, String name, String value);

  /** Replaces the protected model-provider key, without allowing user secrets to overwrite it. */
  void replaceProviderKey(UUID workspaceId, String value);

  /** One secret's value; empty when the workspace has no such secret, or none at all. */
  Optional<String> secret(UUID workspaceId, String name);

  /** Removes one secret; removing one that is not there is not an error. */
  void removeSecret(UUID workspaceId, String name);

  /**
   * Sets one variable the workspace always starts with, over any secret of the same name. A running
   * workspace sees it only once it restarts.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  void setSystemVariable(UUID workspaceId, String name, String value);

  /**
   * The last lines it printed, oldest first; empty when it has none or the log cannot be read.
   * Before its harness starts, this is the only way to hear from it.
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
