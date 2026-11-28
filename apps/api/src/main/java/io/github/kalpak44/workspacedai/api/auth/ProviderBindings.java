package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.provider.*;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable intent before I/O; readiness receipts, never enqueue/status, advance active state. */
@Service
public class ProviderBindings {
  public record Choice(UUID id, String name, String provider, boolean trial) {}

  public record Status(
      Choice active,
      Choice pending,
      List<Choice> choices,
      String phase,
      String error,
      boolean restartRequired) {}

  private record Dispatch(UUID workspace, long revision, String provider, String sealedKey) {
    @Override
    public String toString() {
      return "ProviderDispatch[workspace="
          + workspace
          + ", revision="
          + revision
          + ", provider="
          + provider
          + "]";
    }
  }

  private final AiUsage usage;
  private final WorkspaceRepository workspaces;
  private final AiConnectorRepository connectors;
  private final WorkspaceRuntime runtime;
  private final ApiKeyCheck checks;
  private final SecretBox box;
  private final TransactionTemplate tx;
  private final boolean openaiEnabled;
  private final Clock clock;
  private final Duration timeout;
  private final io.github.kalpak44.workspacedai.api.ClusterLocks locks;
  private static final Duration ROUTING_LEASE = Duration.ofMinutes(10);

  public ProviderBindings(
      WorkspaceRepository workspaces,
      AiUsage usage,
      AiConnectorRepository connectors,
      WorkspaceRuntime runtime,
      ApiKeyCheck checks,
      AuthProperties auth,
      TransactionTemplate tx,
      io.github.kalpak44.workspacedai.api.ClusterLocks locks,
      @Value("${workspaced.ai.openai-api-key-enabled:false}") boolean enabled,
      @Value("${workspaced.ai.provider-restart-timeout:PT3M}") Duration timeout) {
    this.usage = usage;
    this.workspaces = workspaces;
    this.connectors = connectors;
    this.runtime = runtime;
    this.checks = checks;
    this.box = new SecretBox(auth.jwtSecret(), "workspaced-ai-connectors");
    this.tx = tx;
    this.locks = locks;
    this.openaiEnabled = enabled;
    this.clock = Clock.systemUTC();
    this.timeout = timeout;
  }

  public void requireEnabled(String provider) {
    if (provider.equals("openai") && !openaiEnabled)
      throw conflict("Experimental OpenAI API-key binding is disabled");
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private WorkspaceEntity owned(UUID id) {
    return workspaces
        .findById(id)
        .filter(w -> w.getOwnerId().equals(CurrentUser.id()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace"));
  }

  private AiConnectorEntity connector(WorkspaceEntity w, UUID id) {
    return connectors
        .findById(id)
        .filter(c -> c.getOwnerId().equals(w.getOwnerId()))
        .orElseThrow(() -> conflict("Connector is no longer authorized"));
  }

  private String key(AiConnectorEntity c) {
    return box.open(c.encryptedKey()).orElseThrow(() -> conflict("Reconnect the AI connector"));
  }

  private void usable(AiConnectorEntity c) {
    requireEnabled(c.getProvider());
    if (!checks.accepts(ModelProvider.byId(c.getProvider()).orElseThrow(), key(c)))
      throw new ApiKeyRejectedException(ModelProvider.byId(c.getProvider()).orElseThrow());
  }

  public void stage(WorkspaceEntity w, AiConnectorEntity c) {
    var locked = workspaces.findLockedById(w.getId()).orElseThrow();
    var state = locked.getProviderChange();
    if (locked.isDraft()) throw conflict("Launch the workspace before staging a provider change");
    if (state.blocked())
      throw conflict(
          "Wait for the provider restart or recover the blocked binding before selecting another connector");
    usable(c);

    state.pending = c.getId();
    state.targetKey = box.seal(key(c));
    state.phase = "STAGED";
    state.error = null;
    locked.setSecretsPendingRestart(true);
  }

  public Status status(UUID id) {
    var w = owned(id);
    reconcile(id);
    w = owned(id);
    var s = w.getProviderChange();
    return new Status(
        choice(w, w.getAiConnectorId()),
        choice(w, s.pending),
        connectors.findAllByOwnerIdOrderByNameAscIdAsc(w.getOwnerId()).stream()
            .filter(
                c ->
                    c.getProvider().equals("openrouter")
                        || (openaiEnabled && c.getProvider().equals("openai")))
            .map(c -> new Choice(c.getId(), c.getName(), c.getProvider(), c.isTrial()))
            .toList(),
        s.phase,
        s.error,
        w.isSecretsPendingRestart());
  }

  private Choice choice(WorkspaceEntity w, UUID id) {
    return id == null
        ? null
        : connectors
            .findById(id)
            .filter(c -> c.getOwnerId().equals(w.getOwnerId()))
            .map(c -> new Choice(c.getId(), c.getName(), c.getProvider(), c.isTrial()))
            .orElse(null);
  }

  /** Called outside WorkspaceService's transaction. The phase commits before any remote write. */
  public boolean restart(UUID id) {
    return locks.call("provider-routing-" + id, ROUTING_LEASE, () -> restartLocked(id));
  }

  private boolean restartLocked(UUID id) {
    if (workspaces.findById(id).orElseThrow().getProviderChange().phase.equals("NONE"))
      return false;
    var command =
        tx.execute(
            _ -> {
              var w = workspaces.findLockedById(id).orElseThrow();
              var s = w.getProviderChange();
              if (s.phase.equals("NONE")) return null;
              if (s.inFlight()) return dispatch(w);
              if (s.phase.equals("BLOCKED")) {
                if ("SUPERSEDED_RUNTIME".equals(s.error))
                  throw conflict("A newer runtime binding requires operator reconciliation");
                return rollback(w);
              }
              var target = connector(w, s.pending);
              usable(target);
              if (!key(target).equals(box.open(s.targetKey).orElseThrow()))
                throw conflict("Connector changed; select it again");
              s.previousKey =
                  w.getAiConnectorId() == null
                      ? null
                      : box.seal(key(connector(w, w.getAiConnectorId())));
              s.configurationRevision = w.getConfigurationRevision();
              s.phase = "APPLYING";
              s.revision++;
              s.deadline = clock.instant().plus(timeout);
              s.error = null;
              return dispatch(w);
            });
    if (command == null)
      throw conflict("The previous binding cannot be restored; sending remains blocked");
    send(command);
    return true;
  }

  private Dispatch dispatch(WorkspaceEntity w) {
    var s = w.getProviderChange();
    return new Dispatch(
        w.getId(),
        s.revision,
        s.phase.equals("ROLLING_BACK")
            ? w.getModelProvider()
            : connector(w, s.pending).getProvider(),
        s.phase.equals("ROLLING_BACK") ? s.previousKey : s.targetKey);
  }

  private void send(Dispatch command) {
    try {
      usage.fence(command.workspace());
      runtime.restartProvider(
          command.workspace(),
          command.revision(),
          command.provider(),
          box.open(command.sealedKey()).orElseThrow());
    } catch (RuntimeException uncertain) {
      /* Receipt/retry resolves uncertain transport outcomes; no activation here. */
    }
  }

  public void reconcile(UUID id) {
    var row = workspaces.findById(id).orElse(null);
    if (row == null || !row.getProviderChange().inFlight()) return;
    locks.call(
        "provider-routing-" + id,
        ROUTING_LEASE,
        () -> {
          reconcileLocked(id);
          return null;
        });
  }

  private void reconcileLocked(UUID id) {
    var before = workspaces.findById(id).orElse(null);
    if (before == null || !before.getProviderChange().inFlight()) return;
    var revision = before.getProviderChange().revision;
    WorkspaceRuntime.ProviderReceipt receipt = null;
    try {
      receipt = runtime.providerReceipt(id);
    } catch (RuntimeException unavailable) {
      /* Keep durable intent. */
    }
    var observed = receipt;
    var command =
        tx.execute(
            _ -> {
              var w = workspaces.findLockedById(id).orElse(null);
              if (w == null) return null;
              var s = w.getProviderChange();
              if (s.revision != revision || !s.inFlight()) return null;
              if (observed != null
                  && Objects.equals(observed.revision(), revision)
                  && Objects.equals(observed.readyRevision(), revision)) {
                if (s.phase.equals("APPLYING")) {
                  var target = connector(w, s.pending);
                  try {
                    usable(target);
                    if (!key(target).equals(box.open(s.targetKey).orElseThrow()))
                      throw conflict("Target changed");
                  } catch (RuntimeException unauthorized) {
                    return rollback(w);
                  }
                  w.activateProvider(target.getId(), target.getProvider());
                  s.pending = null;
                  s.targetKey = null;
                  s.phase = "NONE";
                  s.previousKey = null;
                  s.deadline = null;
                  s.error = null;
                  if (w.getConfigurationRevision() == s.configurationRevision)
                    w.setSecretsPendingRestart(false);
                } else {
                  s.phase = "STAGED";
                  s.previousKey = null;
                  s.deadline = null;
                  s.error = "RESTART_FAILED_ROLLED_BACK";
                  w.setSecretsPendingRestart(true);
                }
                return null;
              }
              if (observed != null
                  && observed.revision() != null
                  && observed.revision() > revision) {
                s.phase = "BLOCKED";
                s.error = "SUPERSEDED_RUNTIME";
                return null;
              }
              if (clock.instant().isBefore(s.deadline)) return dispatch(w);
              if (s.phase.equals("ROLLING_BACK") || s.previousKey == null) {
                s.phase = "BLOCKED";
                s.error = "RESTART_TIMEOUT";
                return null;
              }
              return rollback(w);
            });
    if (command != null) send(command);
  }

  private Dispatch rollback(WorkspaceEntity w) {
    var s = w.getProviderChange();
    try {
      if (s.previousKey == null) throw conflict("No authorized previous credential snapshot");
      var active = connector(w, w.getAiConnectorId());
      if (!key(active).equals(box.open(s.previousKey).orElseThrow())
          || !checks.accepts(ModelProvider.byId(active.getProvider()).orElseThrow(), key(active)))
        throw conflict("Previous binding unavailable");
      s.phase = "ROLLING_BACK";
      s.revision++;
      s.deadline = clock.instant().plus(timeout);
      s.error = "RESTART_FAILED";
      return dispatch(w);
    } catch (RuntimeException unauthorized) {
      s.phase = "BLOCKED";
      s.error = "PREVIOUS_BINDING_UNAVAILABLE";
      return null;
    }
  }

  /**
   * Active provider/credits are usable while merely staged, never while a transition is ambiguous.
   */
  /** Fence request dispatch against provider restarts, without holding a DB connection. */
  public void route(UUID id, Runnable action) {
    locks.call(
        "provider-routing-" + id,
        ROUTING_LEASE,
        () -> {
          requireRoutable(id);
          action.run();
          return null;
        });
  }

  public void requireRoutable(UUID id) {
    var w =
        workspaces
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace"));
    if (w.getProviderChange().blocked()) throw conflict("Provider restart is not ready");
    if (w.getAiConnectorId() != null) connector(w, w.getAiConnectorId());
  }

  public void requireUnreferenced(UUID connectorId) {
    if (workspaces.findAll().stream()
        .anyMatch(
            w ->
                Objects.equals(w.getAiConnectorId(), connectorId)
                    || Objects.equals(w.getProviderChange().pending, connectorId)))
      throw conflict("Select and activate another connector before disconnecting this one");
  }

  @Scheduled(fixedDelayString = "${workspaced.ai.provider-reconcile-ms:10000}")
  public void reconcile() {
    for (var w : workspaces.findAllByDraftFalse())
      if (w.getProviderChange().inFlight())
        try {
          reconcile(w.getId());
        } catch (RuntimeException failure) {
          /* One unavailable workspace must not starve others. State stays gated. */
        }
  }
}
