package io.github.kalpak44.workspacedai.api.compute;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
   * @param provider the cloud to create it in
   * @param serverType null for the VM manager's default
   * @param openRouterApiKey kept by the VM manager as the VM's system secret
   * @param environment the rest of what the harness starts with, kept by the VM manager apart from
   *     the user's secrets
   * @throws ServerTypeRejectedException when the size is not one on offer
   * @throws ServerTypeSoldOutException when Hetzner has the size sold out
   */
  ProvisionedVm create(
      UUID workspaceId,
      UUID userId,
      ComputeProvider provider,
      String serverType,
      String openRouterApiKey,
      Map<String, String> environment);

  /** The id of the workspace's VM; empty when it has none. */
  Optional<UUID> vmIdFor(UUID workspaceId);

  /**
   * One range of the workspace VM's metrics.
   *
   * @param range live, 1h, 24h, 7d or 30d
   * @throws IllegalStateException when the workspace has no VM
   * @throws MetricsRangeRejectedException when the range is not one offered
   */
  WorkspaceMetrics metricsFor(UUID workspaceId, String range);

  /** Deletes every VM of the workspace; none at all counts as deleted. */
  void deleteFor(UUID workspaceId);

  /**
   * Shuts the workspace's VM down, keeping its disk; stopping a stopped one does nothing.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  void stopFor(UUID workspaceId);

  /**
   * Powers the workspace's stopped VM back on; starting one that is not stopped does nothing.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  void startFor(UUID workspaceId);

  /**
   * Marks the workspace's VM restarting, which has its supervisor recreate the harness with fresh
   * values.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  void restartFor(UUID workspaceId);

  /**
   * Adds a secret to the workspace's VM, which keeps it in Vault.
   *
   * @throws IllegalStateException when the workspace has no VM
   * @throws VmSecretRefusedException when the VM already has it or holds as many as it may
   */
  void putSecret(UUID workspaceId, String name, String value);

  /** Replaces the protected model-provider key, without allowing user secrets to overwrite it. */
  void replaceProviderKey(UUID workspaceId, String value);

  /** Removes a secret from the workspace's VM; one it does not have, or no VM, is not an error. */
  void removeSecret(UUID workspaceId, String name);

  /**
   * Sets one variable of the workspace VM's system environment, which the user can neither list nor
   * override.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  void setSystemVariable(UUID workspaceId, String name, String value);

  /**
   * The workspace VM's address and firewall rules.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  VmFirewall firewallFor(UUID workspaceId);

  /**
   * The workspace VM's addresses and its own name.
   *
   * @throws IllegalStateException when the workspace has no VM
   */
  VmAddress addressFor(UUID workspaceId);

  /**
   * Replaces every rule of the workspace VM's firewall, and answers them as the VM manager keeps
   * them.
   *
   * @throws IllegalStateException when the workspace has no VM
   * @throws FirewallRuleRejectedException when a rule is one Hetzner would refuse
   */
  List<FirewallRule> setFirewall(UUID workspaceId, List<FirewallRule> rules);

  /**
   * Hears every VM change from now on, on a thread of its own, reconnecting for as long as the app
   * runs. Never throws: a VM manager that is not configured is simply never heard from.
   */
  void watch(Consumer<ProvisionedVm> listener);
}
