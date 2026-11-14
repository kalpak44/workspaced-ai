package io.github.kalpak44.workspacedai.api.webserver;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Carries out what a workspace's agent asked of its web server, in a file it wrote with {@code
 * web-server publish}, as if the user had saved it in the app. Only workspaces whose user allowed
 * the agent are looked at, so a request in any other is never read. The harness cannot call the
 * API, so the request waits for the next pass, a few seconds away.
 */
@Component
public class WebServerRequests {

  static final String OFF = "off";

  private static final Logger LOG = LoggerFactory.getLogger(WebServerRequests.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final WebServerService service;

  public WebServerRequests(
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      Harness harness,
      WebServerService service) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${workspaced.web-server.request-interval:PT5S}")
  @SchedulerLock(name = "web-server-requests", lockAtMostFor = "PT5M")
  public void run() {
    for (var workspaceId : workspaces.findWebServerAgentAllowedIds()) {
      try {
        answer(workspaceId);
      } catch (RuntimeException e) {
        LOG.warn("Could not answer the web server request of workspace {}", workspaceId, e);
      }
    }
  }

  /**
   * A refusal is the agent's answer, not a failure: it is written back for its tool to print. A
   * harness that cannot be reached keeps its request for the next pass.
   */
  void answer(UUID workspaceId) {
    var endpoint = runtime.harness(workspaceId).orElse(null);
    if (endpoint == null) {
      return;
    }
    var request = harness.webServerRequest(endpoint).map(String::strip).orElse(null);
    if (request == null) {
      return;
    }
    String result;
    try {
      if (request.matches("(?:add|disable|remove) .+")) {
        var parts = request.split(" ", 2);
        result = service.domainForAgent(workspaceId, parts[0], parts[1]);
      } else if (OFF.equals(request)) {
        service.disableForAgent(workspaceId);
        result = OFF;
      } else if (!request.matches(WebServerService.NAME)) {
        result = "error: not a name: up to 59 lowercase letters, digits and inner hyphens";
      } else {
        result = "on " + service.enableForAgent(workspaceId, request).url();
      }
    } catch (ResponseStatusException e) {
      result = "error: " + e.getReason();
    }
    harness.answerWebServerRequest(endpoint, result);
    LOG.info("Answered the web server request of workspace {}: {}", workspaceId, result);
  }
}
