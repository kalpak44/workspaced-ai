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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps the stored workspace and its runtime together. The row is committed before the runtime is
 * asked for a VM, so that slow call holds no database connection; any failure after that removes
 * both again, so a failed create leaves nothing behind to bill or to list. The other slow calls
 * (checking the provider's key, waiting for a VM to be deleted) run outside any transaction too.
 */
@Service
public class WorkspaceService {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final WorkspaceRepository repository;
  private final WorkspaceRuntime runtime;
  private final ApplicationEventPublisher events;
  private final ApiKeyCheck keyCheck;
  private final Harness harness;
  private final TransactionTemplate transactions;

  public WorkspaceService(
      WorkspaceRepository repository,
      WorkspaceRuntime runtime,
      ApplicationEventPublisher events,
      ApiKeyCheck keyCheck,
      Harness harness,
      TransactionTemplate transactions) {
    this.repository = repository;
    this.runtime = runtime;
    this.events = events;
    this.keyCheck = keyCheck;
    this.harness = harness;
    this.transactions = transactions;
  }

  /** The workspaces one user owns; nobody sees another user's. */
  @Transactional(readOnly = true)
  public List<Workspace> list(UUID ownerId) {
    var entities = repository.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId);
    var statuses = runtime.statuses(entities.stream().map(WorkspaceEntity::getId).toList());
    return entities.stream().map(e -> Workspace.of(e, statuses.get(e.getId()))).toList();
  }

  /**
   * A key, when given, is checked before anything is stored, so a rejected key leaves nothing
   * behind. The workspace receives it as a secret and the base URL as config; neither is kept in
   * the database. The harness gets a password of its own, which only the API and the workspace ever
   * see.
   *
   * @param apiKey null for none
   * @param serverType null for the default size
   */
  public Workspace create(
      UUID ownerId, String name, ModelProvider provider, String apiKey, String serverType) {
    return create(ownerId, name, provider, apiKey, serverType, Map.of());
  }

  /**
   * As {@link #create(UUID, String, ModelProvider, String, String)}, with more secrets for the
   * harness, such as the restore it is to start from.
   */
  public Workspace create(
      UUID ownerId,
      String name,
      ModelProvider provider,
      String apiKey,
      String serverType,
      Map<String, String> harnessSecrets) {
    if (apiKey != null && !keyCheck.accepts(provider, apiKey)) {
      throw new ApiKeyRejectedException(provider);
    }
    var id =
        transactions
            .execute(
                _ -> repository.saveAndFlush(new WorkspaceEntity(ownerId, name, provider.id())))
            .getId();
    try {
      var startedAs =
          runtime.start(
              id,
              ownerId,
              serverType,
              new WorkspaceEnvironment(
                  Map.of(provider.baseUrlVariable(), provider.baseUrl()),
                  secrets(provider, apiKey, harnessSecrets)));
      var entity =
          transactions.execute(
              _ -> {
                var saved = repository.findById(id).orElseThrow();
                saved.setServerType(startedAs);
                return saved;
              });
      return Workspace.of(entity, statusOf(id));
    } catch (RuntimeException e) {
      release(id);
      transactions.executeWithoutResult(_ -> repository.deleteById(id));
      throw e;
    }
  }

  // Best effort: when the runtime cannot be reached either, a VM keeps its workspace-id label and
  // is found by it.
  private void release(UUID workspaceId) {
    try {
      runtime.stop(workspaceId);
    } catch (RuntimeException e) {
      log.error("workspace {} failed to create and could not be released", workspaceId, e);
    }
  }

  private static Map<String, String> secrets(
      ModelProvider provider, String apiKey, Map<String, String> harnessSecrets) {
    var secrets = new HashMap<>(harnessSecrets);
    if (apiKey != null) {
      secrets.put(provider.apiKeyVariable(), apiKey);
    }
    secrets.put(Harness.PASSWORD_VARIABLE, password());
    return secrets;
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
   * The runtime is stopped first, outside any transaction, since that waits for its VM; if it does
   * not stop the row is kept, so the delete can be retried. False when no workspace has that id.
   */
  public boolean delete(UUID id) {
    var owner = repository.findOwnerIdById(id);
    if (owner.isEmpty()) {
      return false;
    }
    runtime.stop(id);
    transactions.executeWithoutResult(
        _ -> {
          repository.deleteById(id);
          events.publishEvent(new WorkspaceDeleted(id, owner.get()));
        });
    return true;
  }

  /**
   * Returns once the reboot is under way; the status stream reports it starting and then ready. It
   * reads the secrets afresh, so none is left pending. False when no workspace has that id.
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

  /** Empty when no workspace has that id. */
  @Transactional(readOnly = true)
  public Optional<UUID> ownerOf(UUID id) {
    return repository.findOwnerIdById(id);
  }

  private WorkspaceStatus statusOf(UUID id) {
    return runtime.statuses(Set.of(id)).get(id);
  }
}
