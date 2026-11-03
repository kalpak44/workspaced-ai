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

/**
 * A workspace's agent settings: its model, and its MCP servers. The database is the source of truth
 * and the harness a copy, so a change is stored and applied in one transaction: a harness that
 * refuses it rolls the change back.
 */
@Service
public class SettingsService {

  private final WorkspaceRepository workspaces;
  private final McpServerRepository servers;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final HarnessSettings settings;

  public SettingsService(
      WorkspaceRepository workspaces,
      McpServerRepository servers,
      WorkspaceRuntime runtime,
      Harness harness,
      HarnessSettings settings) {
    this.workspaces = workspaces;
    this.servers = servers;
    this.runtime = runtime;
    this.harness = harness;
    this.settings = settings;
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

  /** Statuses come from the harness when it is running, and read "unknown" when it is not. */
  @Transactional(readOnly = true)
  public List<McpServer> mcpServers(UUID workspaceId) {
    workspace(workspaceId);
    Map<String, String> statuses;
    try {
      statuses = runtime.harness(workspaceId).map(harness::mcpStatus).orElse(null);
    } catch (HarnessUnavailableException _) {
      statuses = null;
    }
    var known = statuses;
    return servers.findAllByWorkspaceIdOrderByCreatedAtAscNameAsc(workspaceId).stream()
        .map(
            s ->
                new McpServer(
                    s.getName(),
                    s.getUrl(),
                    s.getHeaders().keySet().stream().sorted().toList(),
                    known == null ? "unknown" : known.getOrDefault(s.getName(), "pending")))
        .toList();
  }

  @Transactional
  public McpServer addMcpServer(UUID workspaceId, McpServerRequest request) {
    workspace(workspaceId);
    if (servers.findByWorkspaceIdAndName(workspaceId, request.name()).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "the workspace already has an MCP server " + request.name());
    }
    servers.saveAndFlush(
        new McpServerEntity(workspaceId, request.name(), request.url(), request.headers()));
    settings.apply(workspaceId);
    return mcpServers(workspaceId).stream()
        .filter(s -> s.name().equals(request.name()))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void removeMcpServer(UUID workspaceId, String name) {
    workspace(workspaceId);
    var server =
        servers
            .findByWorkspaceIdAndName(workspaceId, name)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the workspace has no MCP server " + name));
    servers.delete(server);
    servers.flush();
    settings.apply(workspaceId);
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
