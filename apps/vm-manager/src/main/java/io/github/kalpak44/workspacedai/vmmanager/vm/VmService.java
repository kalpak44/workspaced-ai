package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Creates and follows VMs. Nothing is created at Hetzner yet: a created VM stays PROVISIONING until
 * something calls {@link #ready}, the way cloud-init will once VMs are real. Every change is
 * published as a {@link VmEvent}.
 */
@Service
public class VmService {

  private final VmRepository vms;
  private final VmProperties properties;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public VmService(
      VmRepository vms, VmProperties properties, ApplicationEventPublisher events, Clock clock) {
    this.vms = vms;
    this.properties = properties;
    this.events = events;
    this.clock = clock;
  }

  /** Oldest first; only that owners when one is given. */
  public List<Vm> list(Optional<String> ownerRef) {
    return ownerRef.map(vms::findByOwnerRef).orElseGet(vms::findAll);
  }

  public Vm get(UUID id) {
    return vms.findById(id).orElseThrow(() -> new VmNotFoundException(id));
  }

  /**
   * The owner's VM: a second request for the same owner returns the first one's, so a retry never
   * creates two. Synchronized so two concurrent first requests cannot both create.
   *
   * @param serverType blank for the default
   * @throws ServerTypeNotOfferedException when the server type is not one offered
   */
  public synchronized Created create(String ownerRef, String serverType) {
    var type =
        serverType == null || serverType.isBlank()
            ? properties.defaultServerType()
            : serverType.strip();
    if (!properties.offers(type)) {
      throw new ServerTypeNotOfferedException(type, properties.serverTypes());
    }
    var owner = ownerRef.strip();
    var existing = vms.findByOwnerRef(owner);
    if (!existing.isEmpty()) {
      return new Created(existing.getFirst(), false);
    }
    var vm =
        vms.save(
            new Vm(
                UUID.randomUUID(),
                owner,
                type,
                VmStatus.PROVISIONING,
                null,
                clock.instant(),
                null));
    events.publishEvent(new VmEvent.Changed(vm));
    return new Created(vm, true);
  }

  public void delete(UUID id) {
    if (!vms.deleteById(id)) {
      throw new VmNotFoundException(id);
    }
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
