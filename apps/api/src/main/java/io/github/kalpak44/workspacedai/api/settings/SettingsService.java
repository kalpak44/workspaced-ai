package io.github.kalpak44.workspacedai.api.settings;

import io.github.kalpak44.workspacedai.api.ClusterLocks;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.harness.ModelCatalogue;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores the workspace's model preference and delegates MCP management to OpenCode. The harness is
 * never called inside a transaction, so a slow one cannot hold a database connection.
 */
@Service
public class SettingsService {

  private static final Pattern SECRET_REFERENCE = Pattern.compile("\\{env:[A-Za-z_][A-Za-z0-9_]*}");

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final ClusterLocks locks;

  public SettingsService(
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      Harness harness,
      ClusterLocks locks) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
    this.locks = locks;
  }

  @Transactional(readOnly = true)
  public SessionTtlSettings sessionTtl(UUID workspaceId) {
    var workspace = workspace(workspaceId);
    return new SessionTtlSettings(workspace.isSessionTtlEnabled(), workspace.getSessionTtlHours());
  }

  @Transactional
  public SessionTtlSettings setSessionTtl(UUID workspaceId, SessionTtlSettings request) {
    if (request.sessionTtlEnabled() == null
        || request.sessionTtlHours() == null
        || !List.of(1, 6, 24, 72, 168, 720).contains(request.sessionTtlHours())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported session TTL");
    }
    var workspace =
        workspaces
            .lockById(workspaceId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no workspace " + workspaceId));
    workspace.setSessionTtl(request.sessionTtlEnabled(), request.sessionTtlHours());
    return request;
  }

  /** The catalogue lives in the harness, so it needs one running. */
  public ModelCatalogue models(UUID workspaceId) {
    var workspace = workspace(workspaceId);
    return harness.models(endpoint(workspaceId), workspace.getModelProvider());
  }

  /**
   * Checked against the catalogue, so a typo is a 400 here rather than a failed reply later. The
   * check asks the harness, so it happens before the short update rather than inside it.
   */
  public String setModel(UUID workspaceId, String model) {
    workspace(workspaceId);
    if (model != null
        && models(workspaceId).models().stream().noneMatch(m -> m.id().equals(model))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "the workspace's provider offers no agent model " + model);
    }
    if (workspaces.updateModel(workspaceId, model) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId);
    }
    return model;
  }

  public List<McpServer> mcpServers(UUID workspaceId) {
    workspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    return harness.mcpStatus(endpoint).entrySet().stream()
        .map(e -> new McpServer(e.getKey(), e.getValue()))
        .toList();
  }

  /**
   * The harness's config file is read, changed and written back, so two changes to one workspace
   * take turns, whichever API instance each reaches; otherwise the later write would drop the
   * earlier one's server.
   */
  public McpServer addMcpServer(UUID workspaceId, McpServerRequest request) {
    workspace(workspaceId);
    var config = config(request);
    var endpoint = endpoint(workspaceId);
    locks.run(
        mcpLock(workspaceId),
        () -> {
          if (harness.mcpStatus(endpoint).containsKey(request.name())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "the workspace already has an MCP server " + request.name());
          }
          harness.configureMcp(endpoint, Map.of(request.name(), config));
        });
    // Projects rebuilt by the reload list their servers only once they have started them.
    return mcpServers(workspaceId).stream()
        .filter(s -> s.name().equals(request.name()))
        .findFirst()
        .orElse(new McpServer(request.name(), "pending"));
  }

  public void removeMcpServer(UUID workspaceId, String name) {
    workspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    locks.run(
        mcpLock(workspaceId),
        () -> {
          if (!harness.mcpStatus(endpoint).containsKey(name)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "the workspace has no MCP server " + name);
          }
          harness.removeMcp(endpoint, name);
        });
  }

  static String mcpLock(UUID workspaceId) {
    return "mcp-config:" + workspaceId;
  }

  /** Exactly one of a URL or a command; the parts that belong to the other form are refused. */
  private static Harness.McpServerConfig config(McpServerRequest request) {
    var remote = request.url() != null;
    if (remote == (request.command() != null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "an MCP server needs either a url or a command");
    }
    if (remote) {
      if (request.args() != null || request.env() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "args and env belong to a server started by command");
      }
      var headers = request.headers() == null ? Map.<String, String>of() : request.headers();
      validateHeaders(headers);
      return new Harness.McpServerConfig.Remote(request.url(), headers);
    }
    if (request.headers() != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "headers belong to a server reached by url");
    }
    var command = new ArrayList<String>();
    command.add(request.command());
    if (request.args() != null) {
      command.addAll(request.args());
    }
    var environment = new LinkedHashMap<String, String>();
    if (request.env() != null) {
      request
          .env()
          .forEach((variable, value) -> environment.put(variable, secret(variable, value)));
    }
    return new Harness.McpServerConfig.Local(List.copyOf(command), environment);
  }

  /** Empty stands for the secret of the variable's own name, as config files leave them. */
  private static String secret(String variable, String value) {
    if (value.isEmpty()) {
      return "{env:" + variable + "}";
    }
    if (!SECRET_REFERENCE.matcher(value).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "MCP env values must be empty or reference a workspace secret using {env:SECRET_NAME}");
    }
    return value;
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
