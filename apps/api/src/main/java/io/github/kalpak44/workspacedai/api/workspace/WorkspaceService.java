package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the stored workspace and its runtime together. The runtime call happens inside the
 * transaction, after the change is flushed, so a pod that cannot be started or stopped rolls the
 * database back, and a database error never leaves a pod behind.
 */
@Service
public class WorkspaceService {

  private final WorkspaceRepository repository;
  private final WorkspaceRuntime runtime;

  public WorkspaceService(WorkspaceRepository repository, WorkspaceRuntime runtime) {
    this.repository = repository;
    this.runtime = runtime;
  }

  @Transactional(readOnly = true)
  public List<Workspace> list() {
    var entities = repository.findAllByOrderByCreatedAtAscIdAsc();
    var statuses = runtime.statuses(entities.stream().map(WorkspaceEntity::getId).toList());
    return entities.stream().map(e -> Workspace.of(e, statuses.get(e.getId()))).toList();
  }

  @Transactional
  public Workspace create(String name) {
    var entity = repository.saveAndFlush(new WorkspaceEntity(name));
    runtime.start(entity.getId());
    return Workspace.of(entity, statusOf(entity.getId()));
  }

  @Transactional
  public Optional<Workspace> rename(UUID id, String name) {
    return repository
        .findById(id)
        .map(
            entity -> {
              entity.setName(name);
              return Workspace.of(entity, statusOf(id));
            });
  }

  /** False when no workspace has that id. */
  @Transactional
  public boolean delete(UUID id) {
    var entity = repository.findById(id);
    if (entity.isEmpty()) {
      return false;
    }
    repository.delete(entity.get());
    repository.flush();
    runtime.stop(id);
    return true;
  }

  private WorkspaceStatus statusOf(UUID id) {
    return runtime.statuses(Set.of(id)).get(id);
  }
}
