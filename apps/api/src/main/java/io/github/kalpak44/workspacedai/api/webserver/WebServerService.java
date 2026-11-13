package io.github.kalpak44.workspacedai.api.webserver;

import io.github.kalpak44.workspacedai.api.ClusterLocks;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WebServerProperties;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a workspace's web server on under a public name and off again. The name is claimed in the
 * database first, where the unique constraint decides who gets it, and only then is the harness
 * asked to start nginx and the runtime to make it reachable by that name.
 */
@Service
public class WebServerService {

  /**
   * One DNS label, so the name is exactly one level under the domain's wildcard certificate, and
   * short enough that box-<name> stays within the 63 characters of a label and a Service name.
   */
  static final String NAME = "^[a-z0-9](?:[a-z0-9-]{0,57}[a-z0-9])?$";

  /**
   * Every public host is box-<name>, which is all the gateway routes, so no name can ever stand for
   * another host under the domain. The label is the Service's name too, so the gateway in
   * apps/gateway/gateway.conf.template passes it through unchanged.
   */
  static final String HOST_PREFIX = "box-";

  private static final Logger LOG = LoggerFactory.getLogger(WebServerService.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final ClusterLocks locks;
  private final WebServerProperties properties;

  public WebServerService(
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      Harness harness,
      ClusterLocks locks,
      WebServerProperties properties) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
    this.locks = locks;
    this.properties = properties;
  }

  /** Available even when the workspace is stopped. */
  public WebServer get(UUID workspaceId) {
    return view(workspace(workspaceId));
  }

  /** Only stored, so it can be changed while the workspace is stopped. */
  public WebServer setAgentAllowed(UUID workspaceId, boolean allowed) {
    workspace(workspaceId);
    offered();
    workspaces.updateWebServerAgentAllowed(workspaceId, allowed);
    return get(workspaceId);
  }

  /** As {@link #enable}, for the workspace's own agent, which the user has to have allowed. */
  WebServer enableForAgent(UUID workspaceId, String name) {
    agentAllowed(workspaceId);
    return enable(workspaceId, name);
  }

  /** As {@link #disable}, for the workspace's own agent, which the user has to have allowed. */
  void disableForAgent(UUID workspaceId) {
    agentAllowed(workspaceId);
    disable(workspaceId);
  }

  private void agentAllowed(UUID workspaceId) {
    if (!workspace(workspaceId).isWebServerAgentAllowed()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "the user has not let the agent change the web server; they can allow it in the"
              + " workspace's settings, under Web server");
    }
  }

  private void offered() {
    if (!properties.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_IMPLEMENTED, "this instance offers no web server");
    }
  }

  /**
   * Turns it on, or moves it to another name. A failure part way puts the previous name back, so
   * the setting never names a server that is not there.
   */
  public WebServer enable(UUID workspaceId, String name) {
    workspace(workspaceId);
    offered();
    if (properties.reserved().contains(name)) {
      throw taken(name);
    }
    var endpoint = endpoint(workspaceId);
    return locks.call(
        lock(workspaceId),
        () -> {
          var previous = workspace(workspaceId).getWebServerName();
          try {
            workspaces.updateWebServerName(workspaceId, name);
          } catch (DataIntegrityViolationException e) {
            throw taken(name);
          }
          try {
            if (!harness.enableWebServer(endpoint, URI.create(url(name)))) {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT,
                  "the workspace runs an older harness; restart it to update, then try again");
            }
            runtime.publish(workspaceId, label(name));
          } catch (RuntimeException e) {
            rollBack(workspaceId, endpoint, previous);
            throw e;
          }
          return get(workspaceId);
        });
  }

  /** Turning off one that is already off is not an error. */
  public void disable(UUID workspaceId) {
    workspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    locks.run(
        lock(workspaceId),
        () -> {
          harness.disableWebServer(endpoint);
          runtime.unpublish(workspaceId);
          workspaces.updateWebServerName(workspaceId, null);
        });
  }

  /**
   * A server started for a first name stops again, rather than running where nothing routes to it.
   * The rollback's own failure is logged, never thrown over the one that caused it.
   */
  private void rollBack(UUID workspaceId, HarnessEndpoint endpoint, String previous) {
    try {
      workspaces.updateWebServerName(workspaceId, previous);
      if (previous == null) {
        harness.disableWebServer(endpoint);
      }
    } catch (RuntimeException e) {
      LOG.warn("could not roll back the web server of workspace {}", workspaceId, e);
    }
  }

  private WebServer view(WorkspaceEntity workspace) {
    if (!properties.enabled()) {
      return new WebServer(null, null, null, false);
    }
    var name = workspace.getWebServerName();
    return new WebServer(
        properties.domain(),
        name,
        name == null ? null : url(name),
        workspace.isWebServerAgentAllowed());
  }

  private static String label(String name) {
    return HOST_PREFIX + name;
  }

  private String url(String name) {
    return "https://" + label(name) + "." + properties.domain() + "/";
  }

  static String lock(UUID workspaceId) {
    return "web-server:" + workspaceId;
  }

  private static ResponseStatusException taken(String name) {
    return new ResponseStatusException(HttpStatus.CONFLICT, "the name " + name + " is taken");
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
