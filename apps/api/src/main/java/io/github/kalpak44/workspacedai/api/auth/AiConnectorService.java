package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterKeyMetrics;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterMetrics;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.ApiKeyRejectedException;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiConnectorService {
  public record Connector(UUID id, String name, String provider, boolean trial) {}

  private final AiConnectorRepository repository;
  private final ApiKeyCheck check;
  private final SecretBox box;
  private final OpenRouterMetrics metrics;
  private final TrialConnectors trials;
  private final ConnectorAlerts alerts;
  private final UserRepository users;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;

  public AiConnectorService(
      AiConnectorRepository repository,
      ApiKeyCheck check,
      AuthProperties properties,
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      OpenRouterMetrics metrics,
      TrialConnectors trials,
      UserRepository users,
      ConnectorAlerts alerts) {
    this.repository = repository;
    this.check = check;
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.metrics = metrics;
    this.trials = trials;
    this.users = users;
    this.alerts = alerts;
    box = new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors");
  }

  private Connector view(AiConnectorEntity row) {
    return new Connector(row.getId(), row.getName(), row.getProvider(), row.isTrial());
  }

  public List<Connector> list() {
    trials.tryEnsure(CurrentUser.id());
    return repository.findAllByOwnerIdOrderByNameAscIdAsc(CurrentUser.id()).stream()
        .sorted(java.util.Comparator.comparing(AiConnectorEntity::isTrial).reversed())
        .map(this::view)
        .toList();
  }

  @Transactional
  public Connector create(String name, String providerId, String key) {
    var provider = provider(providerId);
    validate(provider, key);
    return view(
        repository.saveAndFlush(
            new AiConnectorEntity(CurrentUser.id(), name, provider.id(), box.seal(key))));
  }

  public AiConnectorEntity owned(UUID id) {
    return repository
        .findByIdAndOwnerId(id, CurrentUser.id())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no AI connector"));
  }

  public String key(UUID id, String providerId) {
    var row = owned(id);
    if (!row.getProvider().equals(providerId))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "connector uses a different model provider");
    return box.open(row.encryptedKey())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "Reconnect this AI connector before using it"));
  }

  @Transactional
  public void acknowledge(UUID id) {
    owned(id).acknowledgeProviderNotice();
  }

  public String keyForWorkspace(UUID connectorId, UUID workspaceId) {
    return key(connectorId, workspace(workspaceId).getModelProvider());
  }

  public OpenRouterKeyMetrics metrics(UUID id) {
    var connector = owned(id);
    if (!connector.getProvider().equals(ModelProvider.OPENROUTER.id()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Usage metrics are unavailable for this provider");
    try {
      var usage = metrics.read(key(id, connector.getProvider()));
      alerts.observe(id, usage);
      return usage;
    } catch (ApiKeyRejectedException refused) {
      alerts.rejected(id);
      throw refused;
    }
  }

  /**
   * Update affected VM secrets before removing the row. On failure keep the connector and restore
   * changed VMs.
   */
  @Transactional
  public void delete(UUID id) {
    // Provision before taking the user lock in this transaction, since ensure has its own
    // transaction.
    var row = owned(id);
    if (row.isTrial())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The Trial connector cannot be deleted");
    var affected = workspaces.findAllByOwnerIdAndAiConnectorId(CurrentUser.id(), id);
    var trial =
        affected.isEmpty()
            ? null
            : trials
                .ensure(CurrentUser.id())
                .orElseThrow(
                    () ->
                        new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Trial fallback is unavailable; the connector was not deleted"));
    users.findLockedById(CurrentUser.id()).orElseThrow();
    row = owned(id);
    affected = workspaces.findAllByOwnerIdAndAiConnectorId(CurrentUser.id(), id);
    var changed = new java.util.ArrayList<UUID>();
    var previousKey = key(id, row.getProvider());
    try {
      for (var workspace : affected) {
        if (trial == null || !trial.getProvider().equals(workspace.getModelProvider()))
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "No compatible trial fallback is available");
        if (!workspace.isDraft()) {
          runtime.replaceProviderKey(workspace.getId(), open(trial));
          changed.add(workspace.getId());
          workspace.setSecretsPendingRestart(true);
        }
        workspace.setAiConnectorId(trial.getId());
      }
      repository.delete(row);
      repository.flush();
    } catch (RuntimeException failure) {
      for (var workspaceId : changed) {
        try {
          runtime.replaceProviderKey(workspaceId, previousKey);
        } catch (RuntimeException restore) {
          failure.addSuppressed(
              new IllegalStateException("Could not restore workspace provider key"));
        }
      }
      throw failure;
    }
  }

  private String open(AiConnectorEntity row) {
    return box.open(row.encryptedKey())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "The connector key is unavailable"));
  }

  @Transactional(readOnly = true)
  public Connector selected(UUID workspaceId) {
    var id = workspace(workspaceId).getAiConnectorId();
    return id == null ? null : view(owned(id));
  }

  @Transactional
  public Connector select(UUID workspaceId, UUID connectorId) {
    users.findLockedById(CurrentUser.id()).orElseThrow();
    var workspace = workspace(workspaceId);
    var key = key(connectorId, workspace.getModelProvider());
    validate(provider(workspace.getModelProvider()), key);
    try {
      runtime.replaceProviderKey(workspaceId, key);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace has no VM yet", e);
    }
    workspace.setSecretsPendingRestart(true);
    remember(workspaceId, connectorId);
    return view(owned(connectorId));
  }

  @Transactional
  public void remember(UUID workspaceId, UUID connectorId) {
    users.findLockedById(CurrentUser.id()).orElseThrow();
    var workspace = workspace(workspaceId);
    key(connectorId, workspace.getModelProvider());
    workspace.setAiConnectorId(connectorId);
  }

  private io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity workspace(UUID id) {
    return workspaces
        .findById(id)
        .filter(w -> w.getOwnerId().equals(CurrentUser.id()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace"));
  }

  private static ModelProvider provider(String id) {
    return ModelProvider.byId(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown model provider"));
  }

  private void validate(ModelProvider provider, String key) {
    if (!check.accepts(provider, key)) throw new ApiKeyRejectedException(provider);
  }
}
