package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Used outside Kubernetes. A laptop's kubeconfig may point at any cluster, so a local run starts
 * nothing and reports every workspace ready.
 */
public class LocalWorkspaceRuntime implements WorkspaceRuntime {

  @Override
  public void start(UUID workspaceId) {
    // Nothing runs locally; the workspace is usable as soon as it is stored.
  }

  @Override
  public void stop(UUID workspaceId) {
    // Nothing was started, so there is nothing to stop.
  }

  @Override
  public Map<UUID, WorkspaceStatus> statuses(Collection<UUID> workspaceIds) {
    return workspaceIds.stream()
        .collect(Collectors.toMap(Function.identity(), id -> WorkspaceStatus.READY));
  }

  @Override
  public void watch(BiConsumer<UUID, WorkspaceStatus> listener) {
    // Every workspace is ready from the start, so no status ever changes.
  }
}
