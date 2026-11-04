package io.github.kalpak44.workspacedai.api.settings;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatusChanged;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts a workspace's MCP servers into its harness. The harness keeps them in its home, which a
 * replaced pod starts without, so they are applied again each time a workspace becomes ready — the
 * API's own restart included, since the first status it hears for each workspace counts.
 */
@Component
public class HarnessSettings {

  private static final Logger LOG = LoggerFactory.getLogger(HarnessSettings.class);

  private final McpServerRepository servers;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  // Workspaces whose current pod has had its servers applied; a status other than READY clears
  // the mark, so the next pod gets them again.
  private final Set<UUID> applied = ConcurrentHashMap.newKeySet();

  public HarnessSettings(McpServerRepository servers, WorkspaceRuntime runtime, Harness harness) {
    this.servers = servers;
    this.runtime = runtime;
    this.harness = harness;
  }

  /** Does nothing for a workspace with no harness running; it gets them when it becomes ready. */
  public void apply(UUID workspaceId) {
    runtime
        .harness(workspaceId)
        .ifPresent(endpoint -> harness.configureMcp(endpoint, desired(workspaceId)));
  }

  /** Off the informer's thread, which must not wait on a call into a pod. */
  @EventListener
  void onStatus(WorkspaceStatusChanged change) {
    if (change.status() != WorkspaceStatus.READY) {
      applied.remove(change.id());
      return;
    }
    if (applied.add(change.id())) {
      Thread.ofVirtual().start(() -> applyReady(change.id()));
    }
  }

  private void applyReady(UUID workspaceId) {
    try {
      apply(workspaceId);
    } catch (HarnessUnavailableException e) {
      // Ready by its probe but not answering yet; the next status report tries again.
      applied.remove(workspaceId);
      LOG.info("could not apply the MCP servers of workspace {}: {}", workspaceId, e.getReason());
    }
  }

  private Map<String, Harness.McpServerConfig> desired(UUID workspaceId) {
    var desired = new LinkedHashMap<String, Harness.McpServerConfig>();
    for (var server : servers.findAllByWorkspaceIdOrderByCreatedAtAscNameAsc(workspaceId)) {
      desired.put(
          server.getName(),
          new Harness.McpServerConfig(server.getUrl(), resolve(workspaceId, server.getHeaders())));
    }
    return desired;
  }

  private Map<String, String> resolve(UUID workspaceId, Map<String, String> headers) {
    var resolved = new LinkedHashMap<String, String>();
    var references = java.util.regex.Pattern.compile("\\{env:([A-Za-z_][A-Za-z0-9_]*)}");
    headers.forEach(
        (name, value) ->
            resolved.put(
                name,
                references
                    .matcher(value)
                    .replaceAll(
                        match ->
                            java.util.regex.Matcher.quoteReplacement(
                                runtime
                                    .environmentValue(workspaceId, match.group(1))
                                    .orElse("")))));
    return resolved;
  }
}
