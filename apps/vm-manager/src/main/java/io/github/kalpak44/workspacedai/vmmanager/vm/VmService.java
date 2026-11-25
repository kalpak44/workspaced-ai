package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.agent.AgentHub;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.CreatedServer;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerSpec;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Creates VMs as Hetzner servers and follows them. A created VM stays PROVISIONING until something
 * calls {@link #ready}, which the VM itself will do once it boots. Every change is published as a
 * {@link VmEvent}.
 */
@Service
public class VmService {

  private static final Logger log = LoggerFactory.getLogger(VmService.class);

  // What Hetzner answers a create with when it has no capacity for the type.
  private static final int RESOURCE_UNAVAILABLE = 412;

  // Creates for one workspace queue on its lock; creates for others run alongside, so one slow
  // create does not hold every other past the API's timeout.
  private final Object[] creating = new Object[64];

  private final VmRepository vms;
  private final VmOptionService options;
  private final HetznerClient hetzner;
  private final HetznerProperties hetznerProperties;
  private final VmInventory inventory;
  private final VmSecretService secrets;
  private final VmFirewallService firewalls;
  private final AgentHub agentHub;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public VmService(
      VmRepository vms,
      VmOptionService options,
      HetznerClient hetzner,
      HetznerProperties hetznerProperties,
      VmInventory inventory,
      VmSecretService secrets,
      VmFirewallService firewalls,
      AgentHub agentHub,
      ApplicationEventPublisher events,
      Clock clock) {
    this.vms = vms;
    this.options = options;
    this.hetzner = hetzner;
    this.hetznerProperties = hetznerProperties;
    this.inventory = inventory;
    this.secrets = secrets;
    this.firewalls = firewalls;
    this.agentHub = agentHub;
    this.events = events;
    this.clock = clock;
    for (var i = 0; i < creating.length; i++) {
      creating[i] = new Object();
    }
  }

  /** Oldest first; only that workspace's when one is given. */
  public List<Vm> list(Optional<UUID> workspaceId) {
    return workspaceId.map(vms::findByWorkspaceId).orElseGet(vms::findAll);
  }

  public Vm get(UUID id) {
    return vms.findById(id).orElseThrow(() -> new VmNotFoundException(id));
  }

  /**
   * The workspace's VM: a second request for the same workspace returns the first one's, so a retry
   * never creates a second server. Locked by workspace so two concurrent first requests cannot both
   * create one. The OpenRouter key and the harness environment go to Vault before the server is
   * asked for, so a VM never boots without them; nothing is kept when either refuses. A second
   * request's key and environment are ignored.
   *
   * @param userId who owns the workspace
   * @param serverType blank for the default
   * @param openRouterApiKey kept as the VM's system secret
   * @param environment the harness's system environment, kept apart from the user's secrets
   * @throws ServerTypeNotOfferedException when the server type is not one offered
   * @throws VmInventoryNotLoadedException before the VMs already at Hetzner are known
   */
  public Created create(
      UUID workspaceId,
      UUID userId,
      String serverType,
      String openRouterApiKey,
      Map<String, String> environment) {
    synchronized (creating[Math.floorMod(workspaceId.hashCode(), creating.length)]) {
      return createLocked(workspaceId, userId, serverType, openRouterApiKey, environment);
    }
  }

  private Created createLocked(
      UUID workspaceId,
      UUID userId,
      String serverType,
      String openRouterApiKey,
      Map<String, String> environment) {
    var type =
        serverType == null || serverType.isBlank()
            ? options.defaultServerType()
            : serverType.strip();
    var option =
        options
            .find(type)
            .orElseThrow(
                () ->
                    new ServerTypeNotOfferedException(
                        type, options.list().stream().map(VmOption::serverType).toList()));
    if (!option.available()) {
      throw new ServerTypeSoldOutException(type);
    }
    if (!inventory.isLoaded()) {
      throw new VmInventoryNotLoadedException();
    }
    var existing = vms.findByWorkspaceId(workspaceId);
    if (!existing.isEmpty()) {
      return new Created(existing.getFirst(), false);
    }
    var id = UUID.randomUUID();
    CreatedServer server;
    Long firewall = null;
    try {
      secrets.init(id, openRouterApiKey, environment);
      var userData = agentHub.bootstrap(id).orElse(null);
      firewall = firewalls.create(id, workspaceId);
      server =
          hetzner.createServer(
              new ServerSpec(
                  "vm-" + id,
                  type,
                  option.location(),
                  hetznerProperties.image(),
                  hetznerProperties.sshKeys(),
                  Map.of(
                      HetznerVmInventory.MANAGED_BY, HetznerVmInventory.MANAGER,
                      HetznerVmInventory.VM_ID, id.toString(),
                      HetznerVmInventory.WORKSPACE_ID, workspaceId.toString(),
                      HetznerVmInventory.USER_ID, userId.toString()),
                  userData,
                  firewall));
    } catch (RuntimeException e) {
      forget(id, firewall);
      // Hetzner can sell out between the list and the create.
      if (e instanceof HetznerException failure && failure.status() == RESOURCE_UNAVAILABLE) {
        throw new ServerTypeSoldOutException(type);
      }
      throw e;
    }
    var vm =
        vms.save(
            new Vm(
                id,
                workspaceId,
                userId,
                type,
                server.id(),
                VmStatus.PROVISIONING,
                server.ipv4(),
                server.ipv6(),
                clock.instant(),
                null));
    events.publishEvent(new VmEvent.Changed(vm));
    return new Created(vm, true);
  }

  /**
   * The server first, then its secrets and its agent: a VM any of them could not be deleted for is
   * kept, so the delete can retry, and a server already gone counts as deleted on the retry. Its
   * firewall is left to {@link VmFirewallService}, since Hetzner refuses it until the server is
   * fully gone.
   */
  public void delete(UUID id) {
    var vm = get(id);
    hetzner.deleteServer(vm.serverId());
    secrets.deleteAll(id);
    agentHub.revoke(id);
    if (!vms.deleteById(id)) {
      throw new VmNotFoundException(id);
    }
    events.publishEvent(new VmEvent.Deleted(id));
  }

  // Best effort: the create has already failed, and that failure is the one to report.
  private void forget(UUID id, Long firewall) {
    try {
      secrets.deleteAll(id);
      agentHub.revoke(id);
      if (firewall != null) {
        hetzner.deleteFirewall(firewall);
      }
    } catch (RuntimeException e) {
      log.error("VM {} failed to create and could not be fully cleaned up", id, e);
    }
  }

  /** A stopped VM has nothing to reboot, so it stays stopped. */
  public Vm restart(UUID id) {
    return change(
        id, vm -> vm.status() == VmStatus.STOPPED ? vm : vm.withStatus(VmStatus.RESTARTING));
  }

  /**
   * Shuts its server down, keeping its disk, secrets and agent so {@link #start} can bring it back
   * as it was. Stopping one already stopped asks nothing of Hetzner.
   */
  public Vm stop(UUID id) {
    var vm = get(id);
    if (vm.status() == VmStatus.STOPPED) {
      return vm;
    }
    hetzner.shutdownServer(vm.serverId());
    return change(id, stopped -> stopped.withStatus(VmStatus.STOPPED));
  }

  /**
   * Powers a stopped VM's server back on; it is PROVISIONING until it reports ready again. Starting
   * one that is not stopped asks nothing of Hetzner.
   */
  public Vm start(UUID id) {
    var vm = get(id);
    if (vm.status() != VmStatus.STOPPED) {
      return vm;
    }
    hetzner.powerOnServer(vm.serverId());
    return change(id, started -> started.withStatus(VmStatus.PROVISIONING));
  }

  /** A stopped VM's late report leaves it stopped. */
  public Vm ready(UUID id) {
    return change(
        id, vm -> vm.status() == VmStatus.STOPPED ? vm : vm.seen(VmStatus.READY, clock.instant()));
  }

  /** Only refreshes when it was last seen; its status stays as it is. */
  public Vm heartbeat(UUID id) {
    return change(id, vm -> vm.seen(vm.status(), clock.instant()));
  }

  private Vm change(UUID id, UnaryOperator<Vm> update) {
    var changed = vms.update(id, update).orElseThrow(() -> new VmNotFoundException(id));
    events.publishEvent(new VmEvent.Changed(changed));
    return changed;
  }

  /** The VM, and whether this request is the one that created it. */
  public record Created(Vm vm, boolean isNew) {}
}
