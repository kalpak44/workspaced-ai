package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessSession;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses OpenCode's durable last-update timestamp, including model responses, without browser
 * activity tracking.
 */
@Service
public class SessionExpiryService {
  private final WorkspaceRepository workspaces;
  private final WorkspaceSessionRepository sessions;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public SessionExpiryService(
      WorkspaceRepository workspaces,
      WorkspaceSessionRepository sessions,
      WorkspaceRuntime runtime,
      Harness harness) {
    this.workspaces = workspaces;
    this.sessions = sessions;
    this.runtime = runtime;
    this.harness = harness;
  }

  @Transactional(readOnly = true)
  public List<UUID> candidates(UUID workspaceId) {
    var workspace = workspaces.findById(workspaceId).orElse(null);
    if (workspace == null || !workspace.isSessionTtlEnabled()) return List.of();
    var endpoint = runtime.harness(workspaceId).orElse(null);
    if (endpoint == null) return List.of();
    var remote =
        harness.sessions(endpoint).stream()
            .collect(Collectors.toMap(HarnessSession::id, Function.identity()));
    var cutoff = Instant.now().minus(workspace.getSessionTtlHours(), ChronoUnit.HOURS);
    return sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId).stream()
        .filter(session -> expired(session, remote, cutoff))
        .map(WorkspaceSessionEntity::getId)
        .toList();
  }

  /** One expiry per transaction; a failure retains the index entry for a later retry. */
  @Transactional
  public boolean expire(UUID id) {
    var workspaceId = sessions.findWorkspaceId(id).orElse(null);
    if (workspaceId == null) return false;
    // Serialize policy updates and expiry; an acknowledged disable cannot race a later deletion.
    var workspace = workspaces.lockById(workspaceId).orElse(null);
    if (workspace == null || !workspace.isSessionTtlEnabled()) return false;
    var session = sessions.lockByIdAndWorkspaceId(id, workspaceId).orElse(null);
    if (session == null) return false;
    var endpoint = runtime.harness(workspaceId).orElse(null);
    if (endpoint == null) return false;
    // Recheck after locking: a user action or model response may have updated the harness
    // after the candidate list was collected.
    var remote =
        harness.sessions(endpoint).stream()
            .collect(Collectors.toMap(HarnessSession::id, Function.identity()));
    var cutoff = Instant.now().minus(workspace.getSessionTtlHours(), ChronoUnit.HOURS);
    if (!expired(session, remote, cutoff)) return false;
    harness.deleteSession(endpoint, session.getPath(), session.getHarnessSessionId());
    if (session.getPath().startsWith(Harness.SESSIONS_ROOT + "/")) {
      harness.removeDirectory(endpoint, session.getPath());
    }
    sessions.delete(session);
    return true;
  }

  private static boolean expired(
      WorkspaceSessionEntity session, Map<String, HarnessSession> remote, Instant cutoff) {
    var current = remote.get(session.getHarnessSessionId());
    // An already missing conversation can still have a private directory to clean up.
    var updated = current == null ? session.getCreatedAt() : current.updatedAt();
    // Missing metadata is not evidence of inactivity: preserve sessions until the harness reports
    // it.
    return updated != null && !updated.isAfter(cutoff);
  }
}
