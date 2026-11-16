package io.github.kalpak44.workspacedai.api.firewall;

import io.github.kalpak44.workspacedai.api.compute.FirewallRule;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Carries out what a workspace's agent asked of its firewall, in a file its {@code firewall} tool
 * wrote, as if the user had saved it in the app. Only workspaces whose user allowed the agent are
 * looked at, so a request in any other is never read. The harness cannot call the API, so the
 * request waits for the next pass, a few seconds away.
 *
 * <p>A request is one line: {@code list}, {@code open <port>[/tcp|udp] [<source>]}, {@code open
 * icmp [<source>]}, {@code close <port>[/tcp|udp]} or {@code close icmp}.
 */
@Component
public class FirewallRequests {

  private static final Pattern OPEN =
      Pattern.compile("open (?:(icmp)|([0-9]+(?:-[0-9]+)?)(?:/(tcp|udp))?)(?: (\\S+))?");
  private static final Pattern CLOSE =
      Pattern.compile("close (?:(icmp)|([0-9]+(?:-[0-9]+)?)(?:/(tcp|udp))?)");

  private static final Logger LOG = LoggerFactory.getLogger(FirewallRequests.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final FirewallService service;

  public FirewallRequests(
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      Harness harness,
      FirewallService service) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${workspaced.firewall.request-interval:PT5S}")
  @SchedulerLock(name = "firewall-requests", lockAtMostFor = "PT5M")
  public void run() {
    for (var workspaceId : workspaces.findFirewallAgentAllowedIds()) {
      try {
        answer(workspaceId);
      } catch (RuntimeException e) {
        LOG.warn("Could not answer the firewall request of workspace {}", workspaceId, e);
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
    var request = harness.firewallRequest(endpoint).map(String::strip).orElse(null);
    if (request == null) {
      return;
    }
    String result;
    try {
      result = describe(carryOut(workspaceId, request));
    } catch (ResponseStatusException e) {
      result = "error: " + e.getReason();
    } catch (IllegalArgumentException e) {
      result = "error: " + e.getMessage();
    }
    harness.answerFirewallRequest(endpoint, result);
    LOG.info("Answered the firewall request of workspace {}: {}", workspaceId, request);
  }

  private WorkspaceFirewall carryOut(UUID workspaceId, String request) {
    if (request.equals("list")) {
      return service.get(workspaceId);
    }
    var open = OPEN.matcher(request);
    if (open.matches()) {
      return service.open(
          workspaceId,
          rule(open.group(1), open.group(2), open.group(3), open.group(4), "Opened by the agent"));
    }
    var close = CLOSE.matcher(request);
    if (close.matches()) {
      return service.close(
          workspaceId, rule(close.group(1), close.group(2), close.group(3), null, null));
    }
    throw new IllegalArgumentException(
        "not a request: list, open <port>[/tcp|udp] [<source>], open icmp [<source>],"
            + " close <port>[/tcp|udp] or close icmp");
  }

  private static FirewallRule rule(
      String icmp, String port, String protocol, String source, String description) {
    if (icmp != null) {
      return new FirewallRule("icmp", null, sources(source), description);
    }
    return new FirewallRule(
        protocol == null ? "tcp" : protocol, port, sources(source), description);
  }

  private static List<String> sources(String source) {
    return source == null ? List.of() : List.of(source);
  }

  /** The address first, then one rule a line, which the tool prints as it is. */
  static String describe(WorkspaceFirewall firewall) {
    var address = firewall.address() == null ? "no address yet" : firewall.address();
    if (firewall.rules().isEmpty()) {
      return address + "\nnothing is open";
    }
    return address
        + "\n"
        + firewall.rules().stream()
            .map(
                r ->
                    r.protocol()
                        + (r.port() == null ? "" : " " + r.port())
                        + " from "
                        + String.join(" ", r.sourceIps()))
            .collect(Collectors.joining("\n"));
  }
}
