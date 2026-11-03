package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Where a workspace actually runs. */
public interface WorkspaceRuntime {

  /** Starts the workspace's runtime; returns before it is ready. */
  void start(UUID workspaceId);

  /** Stops it. Stopping one that is not running is not an error. */
  void stop(UUID workspaceId);

  /** One status per id, in a single lookup however many there are. */
  Map<UUID, WorkspaceStatus> statuses(Collection<UUID> workspaceIds);

  /**
   * Reports every status change from now on. It may repeat a status it already reported, so the
   * listener has to tolerate duplicates.
   */
  void watch(BiConsumer<UUID, WorkspaceStatus> listener);
}
