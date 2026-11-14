package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Where VMs are kept. An interface so the store can move to PostgreSQL without the service above it
 * changing.
 *
 * @see InMemoryVmRepository
 */
public interface VmRepository {

  /** Oldest first. */
  List<Vm> findAll();

  /** Oldest first. */
  List<Vm> findByWorkspaceId(UUID workspaceId);

  Optional<Vm> findById(UUID id);

  Vm save(Vm vm);

  /** Applies the change atomically; empty when there is no such VM. */
  Optional<Vm> update(UUID id, UnaryOperator<Vm> change);

  /** False when there was no such VM. */
  boolean deleteById(UUID id);
}
