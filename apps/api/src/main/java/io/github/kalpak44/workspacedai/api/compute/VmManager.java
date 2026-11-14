package io.github.kalpak44.workspacedai.api.compute;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The VM manager, which creates and deletes the Hetzner VMs workspaces run on. An interface so the
 * workspace service is tested without it. Every method throws {@link VmManagerUnavailableException}
 * when it is not configured or not reachable, and {@link VmManagerFailedException} when it fails.
 *
 * @see HttpVmManager
 */
public interface VmManager {

  /** The sizes on offer, default first, with Hetzner's current specs and prices. */
  List<VmSize> sizes();

  /** Every VM it keeps, for every workspace. */
  List<ProvisionedVm> vms();

  /**
   * The workspace's VM. Idempotent: asking again for the same workspace returns the VM it has.
   *
   * @param serverType null for the VM manager's default
   * @throws ServerTypeRejectedException when the size is not one on offer
   */
  ProvisionedVm create(UUID workspaceId, UUID userId, String serverType);

  /** Deletes every VM of the workspace; none at all counts as deleted. */
  void deleteFor(UUID workspaceId);

  /**
   * Reboots the workspace's VM, keeping its disk.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  void restartFor(UUID workspaceId);

  /**
   * Hears every VM change from now on, on a thread of its own, reconnecting for as long as the app
   * runs. Never throws: a VM manager that is not configured is simply never heard from.
   */
  void watch(Consumer<ProvisionedVm> listener);
}
