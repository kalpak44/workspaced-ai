package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

/**
 * Dummy for now: VMs live in memory and none is created at Hetzner. A created VM stays PROVISIONING
 * until something calls {@link #ready}, the way cloud-init will once VMs are real.
 */
@Service
public class VmService {

  private final Map<UUID, Vm> vms = new ConcurrentHashMap<>();
  private final VmEvents events;
  private final VmProperties properties;

  public VmService(VmEvents events, VmProperties properties) {
    this.events = events;
    this.properties = properties;
  }

  /** Oldest first; only that owner's when one is given. */
  public List<Vm> list(Optional<String> ownerRef) {
    return vms.values().stream()
        .filter(vm -> ownerRef.map(vm.ownerRef()::equals).orElse(true))
        .sorted(Comparator.comparing(Vm::createdAt))
        .toList();
  }

  public Optional<Vm> get(UUID id) {
    return Optional.ofNullable(vms.get(id));
  }

  /**
   * The owner's VM; a second request for the same owner returns the first one's VM.
   *
   * @throws IllegalArgumentException when the server type is not one offered
   */
  public synchronized Created create(CreateVmRequest request) {
    var serverType = request.serverTypeOr(properties.defaultServerType());
    if (!properties.offers(serverType)) {
      throw new IllegalArgumentException(
          "server type "
              + serverType
              + " is not offered; choose one of "
              + String.join(", ", properties.serverTypes()));
    }
    var ownerRef = request.ownerRef().trim();
    var existing = list(Optional.of(ownerRef));
    if (!existing.isEmpty()) {
      return new Created(existing.getFirst(), false);
    }
    var vm =
        new Vm(
            UUID.randomUUID(),
            ownerRef,
            serverType,
            VmStatus.PROVISIONING,
            null,
            Instant.now(),
            null);
    vms.put(vm.id(), vm);
    events.publishStatus(vm);
    return new Created(vm, true);
  }

  /** False when there was no such VM. */
  public boolean delete(UUID id) {
    var removed = vms.remove(id);
    if (removed == null) {
      return false;
    }
    events.publishDeleted(id);
    return true;
  }

  public Optional<Vm> restart(UUID id) {
    return change(id, vm -> vm.withStatus(VmStatus.RESTARTING));
  }

  public Optional<Vm> ready(UUID id) {
    return change(id, vm -> vm.seen(VmStatus.READY, Instant.now()));
  }

  /** Only refreshes when it was last seen; its status stays as it is. */
  public Optional<Vm> heartbeat(UUID id) {
    return change(id, vm -> vm.seen(vm.status(), Instant.now()));
  }

  private Optional<Vm> change(UUID id, UnaryOperator<Vm> update) {
    var changed = Optional.ofNullable(vms.computeIfPresent(id, (_, vm) -> update.apply(vm)));
    changed.ifPresent(events::publishStatus);
    return changed;
  }

  /** The VM, and whether this request is the one that created it. */
  public record Created(Vm vm, boolean isNew) {}
}
