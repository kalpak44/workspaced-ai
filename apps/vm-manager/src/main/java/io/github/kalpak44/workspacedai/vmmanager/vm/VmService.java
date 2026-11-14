package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.agent.AgentHub;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.CreatedServer;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
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

  private final VmRepository vms;
  private final VmProperties properties;
  private final HetznerClient hetzner;
  private final HetznerProperties hetznerProperties;
  private final VmInventory inventory;
  private final VmSecretService secrets;
  private final AgentHub agentHub;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public VmService(
      VmRepository vms,
      VmProperties properties,
      HetznerClient hetzner,
      HetznerProperties hetznerProperties,
      VmInventory inventory,
      VmSecretService secrets,
      AgentHub agentHub,
      ApplicationEventPublisher events,
      Clock clock) {
    this.vms = vms;
    this.properties = properties;
    this.hetzner = hetzner;
    this.hetznerProperties = hetznerProperties;
    this.inventory = inventory;
    this.secrets = secrets;
    this.agentHub = agentHub;
    this.events = events;
    this.clock = clock;
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
   * never creates a second server. Synchronized so two concurrent first requests cannot both create
   * one. The OpenRouter key goes to Vault before the server is asked for, so a VM never runs
   * without it; nothing is kept when either refuses. A second request's key is ignored.
   *
   * @param userId who owns the workspace
   * @param serverType blank for the default
   * @param openRouterApiKey kept as the VM's system secret
   * @throws ServerTypeNotOfferedException when the server type is not one offered
   * @throws VmInventoryNotLoadedException before the VMs already at Hetzner are known
   */
  public synchronized Created create(
      UUID workspaceId, UUID userId, String serverType, String openRouterApiKey) {
    var type =
        serverType == null || serverType.isBlank()
            ? properties.defaultServerType()
            : serverType.strip();
    if (!properties.offers(type)) {
      throw new ServerTypeNotOfferedException(type, properties.serverTypes());
    }
    if (!inventory.isLoaded()) {
      throw new VmInventoryNotLoadedException();
    }
    var existing = vms.findByWorkspaceId(workspaceId);
    if (!existing.isEmpty()) {
      return new Created(existing.getFirst(), false);
    }
    var id = UUID.randomUUID();
    secrets.init(id, openRouterApiKey);
    CreatedServer server;
    try {
      var userData = agentHub.bootstrap(id).orElse(null);
      server =
          hetzner.createServer(
              new ServerSpec(
                  "vm-" + id,
                  type,
                  hetznerProperties.location(),
                  hetznerProperties.image(),
                  hetznerProperties.sshKeys(),
                  Map.of(
                      HetznerVmInventory.MANAGED_BY, HetznerVmInventory.MANAGER,
                      HetznerVmInventory.VM_ID, id.toString(),
                      HetznerVmInventory.WORKSPACE_ID, workspaceId.toString(),
                      HetznerVmInventory.USER_ID, userId.toString()),
                  userData));
    } catch (RuntimeException e) {
      forget(id);
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
                clock.instant(),
                null));
    events.publishEvent(new VmEvent.Changed(vm));
    return new Created(vm, true);
  }

  /**
   * The server first, then its secrets and its agent: a VM any of them could not be deleted for is
   * kept, so the delete can retry, and a server already gone counts as deleted on the retry.
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
  private void forget(UUID id) {
    try {
      secrets.deleteAll(id);
      agentHub.revoke(id);
    } catch (RuntimeException e) {
      log.error("VM {} failed to create and could not be fully cleaned up", id, e);
    }
  }

  public Vm restart(UUID id) {
    return change(id, vm -> vm.withStatus(VmStatus.RESTARTING));
  }

  public Vm ready(UUID id) {
    return change(id, vm -> vm.seen(VmStatus.READY, clock.instant()));
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
