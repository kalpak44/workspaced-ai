package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.ChatMessage;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps each stored session and its harness session together: one row, one opencode session, one
 * directory under the workspace's home.
 */
@Service
public class SessionService {

  private final WorkspaceRepository workspaces;
  private final WorkspaceSessionRepository sessions;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public SessionService(
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
  public List<Session> list(UUID workspaceId) {
    requireWorkspace(workspaceId);
    return sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId).stream()
        .map(Session::of)
        .toList();
  }

  /**
   * The harness session first, since the row needs its id. If the row then cannot be written, the
   * harness session is removed again rather than left with nothing pointing at it.
   */
  @Transactional
  public Session create(UUID workspaceId) {
    requireWorkspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    var id = UUID.randomUUID();
    var path = Harness.SESSIONS_ROOT + "/" + id;
    var harnessId = harness.createSession(endpoint, path, null);
    try {
      return Session.of(
          sessions.saveAndFlush(new WorkspaceSessionEntity(id, workspaceId, path, harnessId)));
    } catch (RuntimeException e) {
      harness.deleteSession(endpoint, path, harnessId);
      throw e;
    }
  }

  /**
   * A workspace whose pod is gone took every session's files with it, so the row is removed even
   * when there is no harness to tell.
   */
  @Transactional
  public void delete(UUID workspaceId, UUID sessionId) {
    var session = session(workspaceId, sessionId);
    sessions.delete(session);
    sessions.flush();
    runtime
        .harness(workspaceId)
        .ifPresent(
            endpoint ->
                harness.deleteSession(endpoint, session.getPath(), session.getHarnessSessionId()));
  }

  @Transactional(readOnly = true)
  public List<ChatMessage> messages(UUID workspaceId, UUID sessionId) {
    var session = session(workspaceId, sessionId);
    return harness.messages(
        endpoint(workspaceId), session.getPath(), session.getHarnessSessionId());
  }

  @Transactional(readOnly = true)
  public void send(UUID workspaceId, UUID sessionId, String text) {
    var session = session(workspaceId, sessionId);
    harness.prompt(endpoint(workspaceId), session.getPath(), session.getHarnessSessionId(), text);
  }

  @Transactional(readOnly = true)
  public void abort(UUID workspaceId, UUID sessionId) {
    var session = session(workspaceId, sessionId);
    harness.abort(endpoint(workspaceId), session.getPath(), session.getHarnessSessionId());
  }

  /** What a stream needs to follow one session; resolved up front, so a 404 is still a 404. */
  @Transactional(readOnly = true)
  public Target target(UUID workspaceId, UUID sessionId) {
    var session = session(workspaceId, sessionId);
    return new Target(
        session.getId(), endpoint(workspaceId), session.getPath(), session.getHarnessSessionId());
  }

  record Target(UUID id, HarnessEndpoint endpoint, String path, String harnessSessionId) {}

  private HarnessEndpoint endpoint(UUID workspaceId) {
    return runtime
        .harness(workspaceId)
        .orElseThrow(
            () -> new HarnessUnavailableException("the workspace's harness is not running"));
  }

  private void requireWorkspace(UUID workspaceId) {
    if (!workspaces.existsById(workspaceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId);
    }
  }

  private WorkspaceSessionEntity session(UUID workspaceId, UUID sessionId) {
    return sessions
        .findByIdAndWorkspaceId(sessionId, workspaceId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "the workspace has no session " + sessionId));
  }
}
