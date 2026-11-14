package io.github.kalpak44.workspacedai.api.secret;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps a workspace's secret names and its runtime's values together, the way WorkspaceService
 * keeps workspaces and pods: the runtime call runs inside the transaction, after the flush, so a
 * failure on either side rolls back both. A running pod reads its secrets only when it starts, so
 * each change marks the workspace as needing a restart.
 */
@Service
public class SecretService {

  /** How many a user may add to one workspace, the provider's key not counted. */
  public static final int MAX_SECRETS = 50;

  // Set by the API for the harness; a user secret of the same name would lock the API out.
  private static final Set<String> HARNESS_VARIABLES =
      Set.of(Harness.PASSWORD_VARIABLE, "OPENCODE_SERVER_PASSWORD", "OPENCODE_SERVER_USERNAME");

  private final WorkspaceRepository workspaces;
  private final WorkspaceSecretRepository secrets;
  private final WorkspaceRuntime runtime;

  public SecretService(
      WorkspaceRepository workspaces, WorkspaceSecretRepository secrets, WorkspaceRuntime runtime) {
    this.workspaces = workspaces;
    this.secrets = secrets;
    this.runtime = runtime;
  }

  /** The provider's key first, which is not stored here, then the user's in the order added. */
  @Transactional(readOnly = true)
  public List<Secret> list(UUID workspaceId) {
    var workspace = workspace(workspaceId);
    var managed =
        ModelProvider.byId(workspace.getModelProvider()).stream()
            .map(p -> new Secret(p.apiKeyVariable(), true));
    var stored =
        secrets.findAllByWorkspaceIdOrderByCreatedAtAscNameAsc(workspaceId).stream()
            .map(e -> new Secret(e.getName(), false));
    return Stream.concat(managed, stored).toList();
  }

  @Transactional
  public Secret add(UUID workspaceId, String name, String value) {
    var workspace = workspace(workspaceId);
    if (HARNESS_VARIABLES.contains(name)) {
      throw conflict(name + " is set by the workspace's harness");
    }
    if (reserved(workspace, name)) {
      throw conflict(name + " is set by the workspace's model provider");
    }
    if (secrets.findByWorkspaceIdAndName(workspaceId, name).isPresent()) {
      throw conflict("the workspace already has a secret " + name);
    }
    // The VM manager holds the same limit; checking here first spares it the call.
    if (secrets.countByWorkspaceId(workspaceId) >= MAX_SECRETS) {
      throw conflict("a workspace holds at most " + MAX_SECRETS + " secrets; remove one first");
    }
    secrets.saveAndFlush(new WorkspaceSecretEntity(workspaceId, name));
    workspace.setSecretsPendingRestart(true);
    try {
      runtime.putSecret(workspaceId, name, value);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace has no VM yet", e);
    }
    return new Secret(name, false);
  }

  @Transactional
  public void remove(UUID workspaceId, String name) {
    var workspace = workspace(workspaceId);
    if (HARNESS_VARIABLES.contains(name)) {
      throw conflict(name + " is set by the workspace's harness");
    }
    if (reserved(workspace, name)) {
      throw conflict(name + " is set by the workspace's model provider");
    }
    var secret =
        secrets
            .findByWorkspaceIdAndName(workspaceId, name)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the workspace has no secret " + name));
    secrets.delete(secret);
    secrets.flush();
    workspace.setSecretsPendingRestart(true);
    runtime.removeSecret(workspaceId, name);
  }

  private WorkspaceEntity workspace(UUID id) {
    return workspaces
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id));
  }

  /** A user secret of the same name would shadow what the provider sets. */
  private static boolean reserved(WorkspaceEntity workspace, String name) {
    return ModelProvider.byId(workspace.getModelProvider())
        .map(p -> p.reserves(name))
        .orElse(false);
  }

  private static ResponseStatusException conflict(String detail) {
    return new ResponseStatusException(HttpStatus.CONFLICT, detail);
  }
}
