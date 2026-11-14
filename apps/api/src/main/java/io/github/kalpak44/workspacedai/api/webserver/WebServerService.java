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
 * Owns each workspace's public domain names. Names are claimed in PostgreSQL before nginx starts;
 * the gateway resolves only enabled names. The singular API remains compatible with older clients
 * and manages the primary name, while the collection manages independent domains.
 */
@Service
public class WebServerService {

  /**
   * One DNS label, so the name is exactly one level under the domain's wildcard certificate, and
   * short enough that box-<name> stays within the 63 characters of a DNS label.
   */
  static final String NAME = "^[a-z0-9](?:[a-z0-9-]{0,57}[a-z0-9])?$";

  /**
   * Every public host is box-<name>, which is all the gateway routes, so no name can ever stand for
   * another host under the domain. The gateway resolves its name through the API.
   */
  static final String HOST_PREFIX = "box-";

  private static final Logger LOG = LoggerFactory.getLogger(WebServerService.class);

  private final WorkspaceRepository workspaces;
  private final WebDomainStore domains;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final ClusterLocks locks;
  private final WebServerProperties properties;

  public WebServerService(
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      Harness harness,
      ClusterLocks locks,
      WebServerProperties properties,
      WebDomainStore domains) {
    this.domains = domains;
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
    return locks.call(
        lock(workspaceId),
        () -> {
          workspaces.updateWebServerAgentAllowed(workspaceId, allowed);
          return get(workspaceId);
        });
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

  String domainForAgent(UUID workspaceId, String operation, String name) {
    agentAllowed(workspaceId);
    if (!name.matches(NAME))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid domain name");
    switch (operation) {
      case "add" -> saveDomain(workspaceId, name, true);
      case "disable" -> saveDomain(workspaceId, name, false);
      case "remove" -> removeDomain(workspaceId, name);
      default ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown domain operation");
    }
    return operation + " " + url(name);
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
          var previous = primaryName(workspaceId);
          var existing = domains.find(name).orElse(null);
          if (existing != null && !existing.workspaceId().equals(workspaceId)) throw taken(name);
          try {
            if (existing == null) domains.claim(workspaceId, name);
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
            if (existing == null) domains.delete(name);
            rollBack(workspaceId, endpoint, previous);
            throw e;
          }
          domains.primary(workspaceId, name);
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
          var name = primaryName(workspaceId);
          if (domains.list(workspaceId).stream()
              .noneMatch(d -> d.enabled() && !d.primaryDomain())) {
            harness.disableWebServer(endpoint);
            runtime.unpublish(workspaceId);
          }
          if (name != null) domains.delete(name);
        });
  }

  /**
   * A server started for a first name stops again, rather than running where nothing routes to it.
   * The rollback's own failure is logged, never thrown over the one that caused it.
   */
  private void rollBack(UUID workspaceId, HarnessEndpoint endpoint, String previous) {
    try {
      if (previous == null
          && domains.list(workspaceId).stream().noneMatch(WebDomainStore.Domain::enabled)) {
        harness.disableWebServer(endpoint);
      } else if (previous != null) {
        harness.enableWebServer(endpoint, URI.create(url(previous)));
      }
    } catch (RuntimeException e) {
      LOG.warn("could not roll back the web server of workspace {}", workspaceId, e);
    }
  }

  private WebServer view(WorkspaceEntity workspace) {
    if (!properties.enabled()) {
      return new WebServer(null, null, null, false);
    }
    var name = primaryName(workspace.getId());
    return new WebServer(
        properties.domain(),
        name,
        name == null ? null : url(name),
        workspace.isWebServerAgentAllowed());
  }

  private String primaryName(UUID workspaceId) {
    return domains.list(workspaceId).stream()
        .filter(d -> d.primaryDomain() && d.enabled())
        .map(WebDomainStore.Domain::name)
        .findFirst()
        .orElse(null);
  }

  public record Domain(String name, String url, boolean enabled) {}

  public java.util.List<Domain> domains(UUID workspaceId) {
    workspace(workspaceId);
    return domains.list(workspaceId).stream()
        .map(d -> new Domain(d.name(), url(d.name()), d.enabled()))
        .toList();
  }

  public java.util.List<Domain> saveDomain(UUID workspaceId, String name, boolean enabled) {
    workspace(workspaceId);
    offered();
    if (!name.matches(NAME))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid domain name");
    if (properties.reserved().contains(name)) throw taken(name);
    return locks.call(
        lock(workspaceId),
        () -> {
          var previous = domains.find(name).orElse(null);
          if (previous != null && !previous.workspaceId().equals(workspaceId)) throw taken(name);
          try {
            if (previous == null) domains.claim(workspaceId, name);
          } catch (DataIntegrityViolationException e) {
            throw taken(name);
          }
          try {
            if (enabled && !harness.enableWebServer(endpoint(workspaceId), URI.create(url(name)))) {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "restart the workspace to update its harness");
            }
            // Disabling a domain removes only its route. nginx and the other domains keep running.
            domains.enabled(name, enabled);
          } catch (RuntimeException e) {
            if (previous == null) domains.delete(name);
            throw e;
          }
          return domains(workspaceId);
        });
  }

  public void removeDomain(UUID workspaceId, String name) {
    workspace(workspaceId);
    locks.run(
        lock(workspaceId),
        () -> {
          var domain = domains.find(name).orElse(null);
          if (domain != null && !domain.workspaceId().equals(workspaceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such domain");
          }
          if (domain != null) domains.delete(name);
        });
  }

  /** Internal gateway lookup; only enabled domains may acquire a tunnel. */
  public String resolveDomain(String name) {
    offered();
    var domain =
        domains
            .find(name)
            .filter(WebDomainStore.Domain::enabled)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return endpoint(domain.workspaceId()).baseUrl().getPath().replaceFirst("/harness$", "/web");
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
