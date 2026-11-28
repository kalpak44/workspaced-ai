package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.provider.OpenRouterMetrics;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Workspace members may see credit availability, but never the owner's connector or key. */
@Service
public class WorkspaceAiCredits {
  private final ProviderBindings bindings;

  public record Status(
      BigDecimal remainingUsd,
      BigDecimal warningThresholdUsd,
      boolean low,
      boolean exhausted,
      boolean trial) {}

  private record Observation(BigDecimal remaining, boolean trial, boolean demo) {}

  private final WorkspaceRepository workspaces;
  private final AiConnectorRepository connectors;
  private final UserRepository users;
  private final OpenRouterMetrics metrics;
  private final SecretBox box;

  public WorkspaceAiCredits(
      WorkspaceRepository workspaces,
      AiConnectorRepository connectors,
      UserRepository users,
      OpenRouterMetrics metrics,
      AuthProperties properties,
      ProviderBindings bindings) {
    this.bindings = bindings;
    this.workspaces = workspaces;
    this.connectors = connectors;
    this.users = users;
    this.metrics = metrics;
    this.box = new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors");
  }

  public Status status(UUID workspaceId, UUID viewerId) {
    var observation = read(workspaceId);
    var threshold =
        observation.trial()
            ? new BigDecimal("0.20")
            : users.findById(viewerId).orElseThrow().getAiCreditWarningUsd();
    var remaining = observation.remaining();
    return new Status(
        remaining,
        threshold,
        (observation.trial() || !observation.demo())
            && remaining != null
            && remaining.compareTo(threshold) < 0,
        remaining != null && remaining.signum() <= 0,
        observation.trial());
  }

  /** A fresh check before every prompt, including edits and resumed form replies. */
  public void withCredits(UUID workspaceId, Runnable dispatch) {
    bindings.route(
        workspaceId,
        () -> {
          requireCredits(workspaceId);
          dispatch.run();
        });
  }

  public void requireCredits(UUID workspaceId) {
    bindings.requireRoutable(workspaceId);
    var remaining = read(workspaceId).remaining();
    if (remaining != null && remaining.signum() <= 0)
      throw new ResponseStatusException(
          HttpStatus.PAYMENT_REQUIRED,
          "AI credits have run out. Ask the workspace owner to top up or select a funded AI connector.");
  }

  private Observation read(UUID id) {
    var workspace =
        workspaces
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace"));
    var connector =
        workspace.getAiConnectorId() == null
            ? null
            : connectors.findById(workspace.getAiConnectorId()).orElse(null);
    if (connector == null || !"openrouter".equals(connector.getProvider()))
      return new Observation(null, false, workspace.isDemo());
    var key =
        box.open(connector.encryptedKey())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "Reconnect the workspace AI connector"));
    var data = metrics.read(key);
    if (data == null) return new Observation(null, connector.isTrial(), workspace.isDemo());
    var remaining = data.limitRemaining();
    if (remaining == null && data.usage() != null && connector.isTrial())
      remaining = (data.limit() == null ? BigDecimal.ONE : data.limit()).subtract(data.usage());
    return new Observation(
        remaining == null ? null : remaining.max(BigDecimal.ZERO),
        connector.isTrial(),
        workspace.isDemo());
  }
}
