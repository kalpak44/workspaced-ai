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
  public record Connector(UUID id, String name, String provider) {}

  private final AiConnectorRepository repository;
  private final ApiKeyCheck check;
  private final SecretBox box;
  private final OpenRouterMetrics metrics;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;

  public AiConnectorService(
      AiConnectorRepository repository,
      ApiKeyCheck check,
      AuthProperties properties,
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      OpenRouterMetrics metrics) {
    this.repository = repository;
    this.check = check;
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.metrics = metrics;
    box = new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors");
  }

  private Connector view(AiConnectorEntity row) {
    return new Connector(row.getId(), row.getName(), row.getProvider());
  }

  public List<Connector> list() {
    return repository.findAllByOwnerIdOrderByNameAscIdAsc(CurrentUser.id()).stream()
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

  public OpenRouterKeyMetrics metrics(UUID id) {
    var connector = owned(id);
    if (!connector.getProvider().equals(ModelProvider.OPENROUTER.id()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Usage metrics are unavailable for this provider");
    return metrics.read(key(id, connector.getProvider()));
  }

  @Transactional
  public void delete(UUID id) {
    repository.delete(owned(id));
    repository.flush();
  }

  @Transactional(readOnly = true)
  public Connector selected(UUID workspaceId) {
    var id = workspace(workspaceId).getAiConnectorId();
    return id == null ? null : view(owned(id));
  }

  @Transactional
  public Connector select(UUID workspaceId, UUID connectorId) {
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
