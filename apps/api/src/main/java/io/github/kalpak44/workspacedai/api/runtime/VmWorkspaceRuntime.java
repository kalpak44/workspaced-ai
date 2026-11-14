package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.compute.ProvisionedVm;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each workspace on a Hetzner VM of its own, created, rebooted and deleted through the VM manager,
 * which also keeps its secrets in Vault. Nothing on a VM serves a harness, reads those secrets or
 * publishes a web server yet, so the rest answers as if the workspace were not running.
 */
public class VmWorkspaceRuntime implements WorkspaceRuntime {

  private final VmManager vmManager;

  public VmWorkspaceRuntime(VmManager vmManager) {
    this.vmManager = vmManager;
  }

  /**
   * Only the provider's key goes anywhere, to the VM manager as the VM's system secret; nothing on
   * the VM would read the rest of the environment yet.
   */
  @Override
  public String start(
      UUID workspaceId, UUID ownerId, String serverType, WorkspaceEnvironment environment) {
    var key = environment.secrets().get(ModelProvider.OPENROUTER.apiKeyVariable());
    return vmManager.create(workspaceId, ownerId, serverType, key).serverType();
  }

  @Override
  public void stop(UUID workspaceId) {
    vmManager.deleteFor(workspaceId);
  }

  @Override
  public void restart(UUID workspaceId) {
    vmManager.restartFor(workspaceId);
  }

  @Override
  public void publish(UUID workspaceId, String label) {
    // Nothing routes to a VM yet.
  }

  @Override
  public void unpublish(UUID workspaceId) {
    // No route was made.
  }

  @Override
  public void putSecret(UUID workspaceId, String name, String value) {
    vmManager.putSecret(workspaceId, name, value);
  }

  /** The VM manager never hands a value back, so none can be reused. */
  @Override
  public Optional<String> secret(UUID workspaceId, String name) {
    return Optional.empty();
  }

  @Override
  public void removeSecret(UUID workspaceId, String name) {
    vmManager.removeSecret(workspaceId, name);
  }

  @Override
  public List<String> recentLog(UUID workspaceId, int lines) {
    return List.of();
  }

  @Override
  public Optional<HarnessEndpoint> harness(UUID workspaceId) {
    return Optional.empty();
  }

  /** A workspace whose VM is gone, deleted by hand or never made, has failed. */
  @Override
  public Map<UUID, WorkspaceStatus> statuses(Collection<UUID> workspaceIds) {
    var vms =
        vmManager.vms().stream()
            .collect(
                Collectors.toMap(ProvisionedVm::workspaceId, Function.identity(), (a, _) -> a));
    return workspaceIds.stream()
        .distinct()
        .collect(
            Collectors.toMap(
                Function.identity(),
                id ->
                    Optional.ofNullable(vms.get(id))
                        .map(VmWorkspaceRuntime::status)
                        .orElse(WorkspaceStatus.FAILED)));
  }

  /**
   * Only changes, never deletions: a VM is deleted as its workspace is, and reporting that as a
   * failure would flash the workspace failed at the moment before it disappears.
   */
  @Override
  public void watch(BiConsumer<UUID, WorkspaceStatus> listener) {
    vmManager.watch(vm -> listener.accept(vm.workspaceId(), status(vm)));
  }

  private static WorkspaceStatus status(ProvisionedVm vm) {
    return "READY".equals(vm.status()) ? WorkspaceStatus.READY : WorkspaceStatus.INITIALIZING;
  }
}
