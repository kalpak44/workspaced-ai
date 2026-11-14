package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stands in for the VMs, so the API is tested without a VM manager: it starts nothing, reports
 * every workspace ready, and points every workspace at the one harness a test started.
 */
public class FakeWorkspaceRuntime implements WorkspaceRuntime {

  private final Optional<HarnessEndpoint> harness;

  /** Every workspace shares the one harness the test started, or has none. */
  public FakeWorkspaceRuntime(URI harnessUrl, String password) {
    this.harness = Optional.ofNullable(harnessUrl).map(url -> new HarnessEndpoint(url, password));
  }

  @Override
  public String start(
      UUID workspaceId, UUID ownerId, String serverType, WorkspaceEnvironment environment) {
    // Nothing runs locally; the workspace is usable as soon as it is stored.
    return null;
  }

  @Override
  public void stop(UUID workspaceId) {
    // Nothing was started, so there is nothing to stop.
  }

  @Override
  public void restart(UUID workspaceId) {
    // Nothing runs locally, so there is nothing to restart.
  }

  @Override
  public void publish(UUID workspaceId, String label) {
    // Nothing routes to a laptop, so there is no route to make.
  }

  @Override
  public void unpublish(UUID workspaceId) {
    // No route was made.
  }

  @Override
  public void putSecret(UUID workspaceId, String name, String value) {
    // Nothing runs that could read it, so the value is not kept anywhere.
  }

  @Override
  public Optional<String> secret(UUID workspaceId, String name) {
    // None was kept.
    return Optional.empty();
  }

  @Override
  public void removeSecret(UUID workspaceId, String name) {
    // Nothing was kept, so there is nothing to remove.
  }

  @Override
  public List<String> recentLog(UUID workspaceId, int lines) {
    // Nothing runs locally, so nothing prints.
    return List.of();
  }

  @Override
  public Optional<HarnessEndpoint> harness(UUID workspaceId) {
    return harness;
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
