package io.github.kalpak44.workspacedai.api.settings;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.harness.ModelCatalogue;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stores the workspace's model preference and delegates MCP management to OpenCode. */
@Service
public class SettingsService {

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public SettingsService(
      WorkspaceRepository workspaces, WorkspaceRuntime runtime, Harness harness) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
  }

  /** The catalogue lives in the harness, so it needs one running. */
  @Transactional(readOnly = true)
  public ModelCatalogue models(UUID workspaceId) {
    var workspace = workspace(workspaceId);
    return harness.models(endpoint(workspaceId), workspace.getModelProvider());
  }

  /** Checked against the catalogue, so a typo is a 400 here rather than a failed reply later. */
  @Transactional
  public String setModel(UUID workspaceId, String model) {
    var workspace = workspace(workspaceId);
    if (model != null
        && models(workspaceId).models().stream().noneMatch(m -> m.id().equals(model))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "the workspace's provider offers no agent model " + model);
    }
    workspace.setModel(model);
    return model;
  }

  @Transactional
  public List<McpServer> mcpServers(UUID workspaceId) {
    workspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    return harness.mcpStatus(endpoint).entrySet().stream()
        .map(e -> new McpServer(e.getKey(), e.getValue()))
        .toList();
  }

  @Transactional
  public McpServer addMcpServer(UUID workspaceId, McpServerRequest request) {
    workspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    if (harness.mcpStatus(endpoint).containsKey(request.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "the workspace already has an MCP server " + request.name());
    }
    validateHeaders(request.headers());
    harness.configureMcp(
        endpoint,
        Map.of(request.name(), new Harness.McpServerConfig(request.url(), request.headers())));
    return mcpServers(workspaceId).stream()
        .filter(s -> s.name().equals(request.name()))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void removeMcpServer(UUID workspaceId, String name) {
    workspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    if (!harness.mcpStatus(endpoint).containsKey(name)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "the workspace has no MCP server " + name);
    }
    harness.removeMcp(endpoint, name);
  }

  private static void validateHeaders(Map<String, String> headers) {
    headers.forEach(
        (name, value) -> {
          if (!value.matches("(?:(?:Bearer|Basic) )?\\{env:[A-Za-z_][A-Za-z0-9_]*}")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "MCP header values must reference a workspace secret using {env:SECRET_NAME}");
          }
        });
  }

  private HarnessEndpoint endpoint(UUID workspaceId) {
    return runtime
        .harness(workspaceId)
        .orElseThrow(
            () -> new HarnessUnavailableException("the workspace's harness is not running"));
  }

  private WorkspaceEntity workspace(UUID id) {
    return workspaces
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id));
  }
}
