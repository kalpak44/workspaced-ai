package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceEnvironment;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps the stored workspace and its runtime together. The runtime call happens inside the
 * transaction, after the change is flushed, so a pod that cannot be started or stopped rolls the
 * database back, and a database error never leaves a pod behind.
 */
@Service
public class WorkspaceService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final WorkspaceRepository repository;
  private final WorkspaceRuntime runtime;
  private final ApplicationEventPublisher events;
  private final ApiKeyCheck keyCheck;
  private final Harness harness;

  public WorkspaceService(
      WorkspaceRepository repository,
      WorkspaceRuntime runtime,
      ApplicationEventPublisher events,
      ApiKeyCheck keyCheck,
      Harness harness) {
    this.repository = repository;
    this.runtime = runtime;
    this.events = events;
    this.keyCheck = keyCheck;
    this.harness = harness;
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
   * The harness gets a password of its own, which only the API and the pod ever see.
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
            Map.of(provider.apiKeyVariable(), apiKey, Harness.PASSWORD_VARIABLE, password())));
    return Workspace.of(entity, statusOf(entity.getId()));
  }

  private static String password() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

  /**
   * Returns once the old pod is on its way out; the status stream reports the new one starting and
   * then ready. The new pod reads the secrets afresh, so none is left pending. False when no
   * workspace has that id.
   */
  @Transactional
  public boolean restart(UUID id) {
    var entity = repository.findById(id);
    if (entity.isEmpty()) {
      return false;
    }
    entity.get().setSecretsPendingRestart(false);
    try {
      runtime.restart(id);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "workspace cannot be restarted", e);
    }
    return true;
  }

  /** Opens a terminal only for a workspace that still exists in this application. */
  @Transactional(readOnly = true)
  public WorkspaceTerminal terminal(UUID id, OutputStream output, Runnable onExit) {
    if (!repository.existsById(id)) {
      throw new IllegalArgumentException("no workspace " + id);
    }
    var endpoint =
        runtime
            .harness(id)
            .orElseThrow(() -> new IllegalStateException("workspace OpenCode is not running"));
    return harness.terminal(endpoint, Harness.PROJECT_ROOT, output, onExit);
  }

  private WorkspaceStatus statusOf(UUID id) {
    return runtime.statuses(Set.of(id)).get(id);
  }
}
