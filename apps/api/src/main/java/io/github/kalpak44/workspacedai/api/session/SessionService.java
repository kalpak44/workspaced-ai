package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.ChatMessage;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

  /** Reconciles the durable index with OpenCode before returning it. */
  @Transactional
  public List<Session> list(UUID workspaceId) {
    requireWorkspace(workspaceId);
    var endpoint = runtime.harness(workspaceId);
    var stored = sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId);
    if (endpoint.isPresent()) {
      try {
        var remote =
            harness.sessions(endpoint.get()).stream()
                .collect(Collectors.toMap(s -> s.id(), Function.identity()));
        for (var entity : stored) {
          var current = remote.get(entity.getHarnessSessionId());
          entity.setAvailable(current != null);
          if (current == null) {
            continue;
          } else if (current.title() != null && !current.title().equals(entity.getTitle())) {
            entity.setTitle(current.title());
          }
        }
        sessions.flush();
        stored = sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId);
      } catch (HarnessUnavailableException _) {
        // The durable index remains useful while a replacement pod is starting.
      }
    }
    return stored.stream().map(Session::of).toList();
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
    var path = Harness.PROJECT_ROOT;
    harness.validate(endpoint, path);
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

  /** With the workspace's chosen model, so a change of model applies from the next message. */
  @Transactional(readOnly = true)
  public void send(UUID workspaceId, UUID sessionId, String text) {
    var session = session(workspaceId, sessionId);
    var workspace = workspaces.findById(workspaceId).orElseThrow();
    var model =
        workspace.getModel() == null
            ? new Harness.ModelRef(
                workspace.getModelProvider(),
                harness.models(endpoint(workspaceId), workspace.getModelProvider()).defaultModel())
            : new Harness.ModelRef(workspace.getModelProvider(), workspace.getModel());
    harness.prompt(
        endpoint(workspaceId), session.getPath(), session.getHarnessSessionId(), text, model);
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
        workspaceId,
        session.getId(),
        endpoint(workspaceId),
        session.getPath(),
        session.getHarnessSessionId());
  }

  record Target(
      UUID workspaceId, UUID id, HarnessEndpoint endpoint, String path, String harnessSessionId) {}

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
