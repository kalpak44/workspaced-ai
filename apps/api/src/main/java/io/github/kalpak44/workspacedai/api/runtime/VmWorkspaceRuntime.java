package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.compute.ProvisionedVm;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.compute.VmManagerFailedException;
import io.github.kalpak44.workspacedai.api.compute.VmManagerUnavailableException;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each workspace on a Hetzner VM of its own, created, restarted and deleted through the VM manager,
 * which also keeps its secrets and harness environment in Vault for the VM's supervisor to start
 * the harness with. The harness is reached through the agent hub's tunnel to the VM. Nothing
 * publishes a web server yet.
 */
public class VmWorkspaceRuntime implements WorkspaceRuntime {

  private final VmManager vmManager;
  private final AgentHubProperties agentHub;

  // A workspace keeps its one VM until it is deleted, so its id never needs refreshing, and the
  // harness is asked for on every call to it.
  private final ConcurrentMap<UUID, UUID> vmIds = new ConcurrentHashMap<>();

  public VmWorkspaceRuntime(VmManager vmManager, AgentHubProperties agentHub) {
    this.vmManager = vmManager;
    this.agentHub = agentHub;
  }

  /**
   * The provider's key goes as the VM's system secret and everything else as its harness
   * environment, which the VM manager keeps apart so the user can neither list nor remove it.
   */
  @Override
  public String start(
      UUID workspaceId, UUID ownerId, String serverType, WorkspaceEnvironment environment) {
    var keyVariable = ModelProvider.OPENROUTER.apiKeyVariable();
    var rest = new HashMap<>(environment.config());
    rest.putAll(environment.secrets());
    var key = rest.remove(keyVariable);
    return vmManager.create(workspaceId, ownerId, serverType, key, rest).serverType();
  }

  @Override
  public void stop(UUID workspaceId) {
    vmManager.deleteFor(workspaceId);
    vmIds.remove(workspaceId);
  }

  /** The VM turns RESTARTING, and its supervisor recreates the harness with fresh values. */
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

  /**
   * The agent hub's tunnel to the workspace's VM. No password: the VM's supervisor sets the
   * harness's own on every call, so it never leaves the VM. A VM manager that cannot be asked reads
   * as no harness, the same as a VM that is not connected.
   */
  @Override
  public Optional<HarnessEndpoint> harness(UUID workspaceId) {
    if (!agentHub.isConfigured()) {
      return Optional.empty();
    }
    return vmId(workspaceId)
        .map(
            vm ->
                new HarnessEndpoint(
                    URI.create(
                        agentHub.url().toString().replaceAll("/+$", "")
                            + "/internal/vms/"
                            + vm
                            + "/harness"),
                    ""));
  }

  private Optional<UUID> vmId(UUID workspaceId) {
    var known = vmIds.get(workspaceId);
    if (known != null) {
      return Optional.of(known);
    }
    try {
      var found = vmManager.vmIdFor(workspaceId);
      found.ifPresent(vm -> vmIds.put(workspaceId, vm));
      return found;
    } catch (VmManagerUnavailableException | VmManagerFailedException e) {
      return Optional.empty();
    }
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
