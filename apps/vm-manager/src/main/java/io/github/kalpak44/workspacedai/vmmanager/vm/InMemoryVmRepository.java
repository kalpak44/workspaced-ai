package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/** Keeps VMs for as long as the process runs; a restart forgets them. */
@Repository
public class InMemoryVmRepository implements VmRepository {

  // The id breaks ties, so two VMs created in the same instant still list in one order.
  private static final Comparator<Vm> OLDEST_FIRST =
      Comparator.comparing(Vm::createdAt).thenComparing(Vm::id);

  private final Map<UUID, Vm> vms = new ConcurrentHashMap<>();

  @Override
  public List<Vm> findAll() {
    return vms.values().stream().sorted(OLDEST_FIRST).toList();
  }

  @Override
  public List<Vm> findByWorkspaceId(UUID workspaceId) {
    return vms.values().stream()
        .filter(vm -> vm.workspaceId().equals(workspaceId))
        .sorted(OLDEST_FIRST)
        .toList();
  }

  @Override
  public Optional<Vm> findById(UUID id) {
    return Optional.ofNullable(vms.get(id));
  }

  @Override
  public Vm save(Vm vm) {
    vms.put(vm.id(), vm);
    return vm;
  }

  @Override
  public Optional<Vm> update(UUID id, UnaryOperator<Vm> change) {
    return Optional.ofNullable(vms.computeIfPresent(id, (_, vm) -> change.apply(vm)));
  }

  @Override
  public boolean deleteById(UUID id) {
    return vms.remove(id) != null;
  }
}
