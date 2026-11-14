package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerSpec;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Creates VMs as Hetzner servers and follows them. A created VM stays PROVISIONING until something
 * calls {@link #ready}, which the VM itself will do once it boots. Every change is published as a
 * {@link VmEvent}.
 */
@Service
public class VmService {

  private final VmRepository vms;
  private final VmProperties properties;
  private final HetznerClient hetzner;
  private final HetznerProperties hetznerProperties;
  private final UserPlacementGroups placementGroups;
  private final VmInventory inventory;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public VmService(
      VmRepository vms,
      VmProperties properties,
      HetznerClient hetzner,
      HetznerProperties hetznerProperties,
      UserPlacementGroups placementGroups,
      VmInventory inventory,
      ApplicationEventPublisher events,
      Clock clock) {
    this.vms = vms;
    this.properties = properties;
    this.hetzner = hetzner;
    this.hetznerProperties = hetznerProperties;
    this.placementGroups = placementGroups;
    this.inventory = inventory;
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
   * one. Nothing is kept when Hetzner refuses the server. The server joins its user's spread
   * placement group, so none of their VMs share a physical host.
   *
   * @param userId who owns the workspace
   * @param serverType blank for the default
   * @throws ServerTypeNotOfferedException when the server type is not one offered
   * @throws VmInventoryNotLoadedException before the VMs already at Hetzner are known
   */
  public synchronized Created create(UUID workspaceId, UUID userId, String serverType) {
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
    var server =
        hetzner.createServer(
            new ServerSpec(
                "vm-" + id,
                type,
                hetznerProperties.location(),
                hetznerProperties.image(),
                hetznerProperties.sshKeys(),
                placementGroups.groupFor(userId),
                Map.of(
                    HetznerVmInventory.MANAGED_BY, HetznerVmInventory.MANAGER,
                    HetznerVmInventory.VM_ID, id.toString(),
                    HetznerVmInventory.WORKSPACE_ID, workspaceId.toString(),
                    HetznerVmInventory.USER_ID, userId.toString())));
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
   * The server first: a VM whose server could not be deleted is kept, so the delete can retry. Then
   * its user's placement group, if that server was the last in it. Synchronized with {@link
   * #create}, so a group is never deleted just as a new VM is put in it.
   */
  public synchronized void delete(UUID id) {
    var vm = get(id);
    hetzner.deleteServer(vm.serverId());
    if (!vms.deleteById(id)) {
      throw new VmNotFoundException(id);
    }
    placementGroups.releaseIfEmpty(vm.userId(), vm.serverId());
    events.publishEvent(new VmEvent.Deleted(id));
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
