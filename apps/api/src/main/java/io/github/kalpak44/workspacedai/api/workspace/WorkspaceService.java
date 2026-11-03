package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceEnvironment;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
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
  private final ApplicationEventPublisher events;
  private final ApiKeyCheck keyCheck;

  public WorkspaceService(
      WorkspaceRepository repository,
      WorkspaceRuntime runtime,
      ApplicationEventPublisher events,
      ApiKeyCheck keyCheck) {
    this.repository = repository;
    this.runtime = runtime;
    this.events = events;
    this.keyCheck = keyCheck;
  }

  @Transactional(readOnly = true)
  public List<Workspace> list() {
    var entities = repository.findAllByOrderByCreatedAtAscIdAsc();
    var statuses = runtime.statuses(entities.stream().map(WorkspaceEntity::getId).toList());
    return entities.stream().map(e -> Workspace.of(e, statuses.get(e.getId()))).toList();
  }

  /**
   * The key is checked before anything is stored, so a rejected key leaves nothing behind. The
   * workspace receives it as a secret and the base URL as config; neither is kept in the database.
   */
  @Transactional
  public Workspace create(String name, ModelProvider provider, String apiKey) {
    if (!keyCheck.accepts(provider, apiKey)) {
      throw new ApiKeyRejectedException(provider);
    }
    var entity = repository.saveAndFlush(new WorkspaceEntity(name, provider.id()));
    runtime.start(
        entity.getId(),
        new WorkspaceEnvironment(
            Map.of(provider.baseUrlVariable(), provider.baseUrl()),
            Map.of(provider.apiKeyVariable(), apiKey)));
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

  /**
   * Returns once the workspace's runtime is gone, so the caller can show it deleting until then.
   * False when no workspace has that id.
   */
  @Transactional
  public boolean delete(UUID id) {
    var entity = repository.findById(id);
    if (entity.isEmpty()) {
      return false;
    }
    repository.delete(entity.get());
    repository.flush();
    runtime.stop(id);
    events.publishEvent(new WorkspaceDeleted(id));
    return true;
  }

  private WorkspaceStatus statusOf(UUID id) {
    return runtime.statuses(Set.of(id)).get(id);
  }
}
