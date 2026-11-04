package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.AgentForm;
import io.github.kalpak44.workspacedai.api.harness.ChatMessage;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        // Updated by query, not by changing the entities: a session deleted while this runs
        // would otherwise fail the whole list when its row turns out to be gone.
        for (var entity : List.copyOf(stored)) {
          var current = remote.get(entity.getHarnessSessionId());
          var available = current != null;
          var title = available && current.title() != null ? current.title() : entity.getTitle();
          if (available != entity.isAvailable() || !Objects.equals(title, entity.getTitle())) {
            sessions.reconcile(entity.getId(), available, title);
          }
        }
        stored = sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId);
      } catch (HarnessUnavailableException _) {
        // The durable index remains useful while a replacement pod is starting.
      }
    }
    return stored.stream().map(Session::of).toList();
  }

  /**
   * The session's own folder, a project of its own to OpenCode, then the harness session, since the
   * row needs its id. Whatever was made is removed again if a later step fails, rather than left
   * with nothing pointing at it.
   */
  @Transactional
  public Session create(UUID workspaceId) {
    requireWorkspace(workspaceId);
    var endpoint = endpoint(workspaceId);
    var id = UUID.randomUUID();
    var path = Harness.SESSIONS_ROOT + "/" + id;
    harness.createDirectory(endpoint, path);
    String harnessId = null;
    try {
      harness.validate(endpoint, path);
      harnessId = harness.createSession(endpoint, path, null);
      return Session.of(
          sessions.saveAndFlush(new WorkspaceSessionEntity(id, workspaceId, path, harnessId)));
    } catch (RuntimeException e) {
      if (harnessId != null) {
        harness.deleteSession(endpoint, path, harnessId);
      }
      harness.removeDirectory(endpoint, path);
      throw e;
    }
  }

  /**
   * The session's folder goes with it. One in the shared project, from before sessions had their
   * own, is kept. The row is removed even when there is no harness to tell, as when the workspace
   * is not running.
   */
  @Transactional
  public void delete(UUID workspaceId, UUID sessionId) {
    var session = sessionForUpdate(workspaceId, sessionId);
    sessions.delete(session);
    sessions.flush();
    runtime
        .harness(workspaceId)
        .ifPresent(
            endpoint -> {
              harness.deleteSession(endpoint, session.getPath(), session.getHarnessSessionId());
              if (session.getPath().startsWith(Harness.SESSIONS_ROOT + "/")) {
                harness.removeDirectory(endpoint, session.getPath());
              }
            });
  }

  @Transactional(readOnly = true)
  public List<ChatMessage> messages(UUID workspaceId, UUID sessionId) {
    var session = session(workspaceId, sessionId);
    return harness.messages(
        endpoint(workspaceId), session.getPath(), session.getHarnessSessionId());
  }

  @Transactional(readOnly = true)
  public AgentSelection agents(UUID workspaceId, UUID sessionId) {
    var target = target(workspaceId, sessionId);
    var options =
        harness.agents(target.endpoint(), target.path()).stream()
            .filter(
                agent ->
                    !agent.hidden()
                        && ("primary".equals(agent.mode()) || "all".equals(agent.mode())))
            .map(
                agent ->
                    new AgentSelection.Option(
                        agent.id(), agent.name(), agent.description(), agent.mode()))
            .toList();
    return new AgentSelection(
        harness.sessionAgent(target.endpoint(), target.harnessSessionId()), options);
  }

  @Transactional
  public AgentSelection setAgent(UUID workspaceId, UUID sessionId, String agentId) {
    sessionForUpdate(workspaceId, sessionId);
    var target = target(workspaceId, sessionId);
    var selection = agents(workspaceId, sessionId);
    if (selection.agents().stream().noneMatch(agent -> agent.id().equals(agentId))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "this agent cannot be selected for a session");
    }
    if (!agentId.equals(selection.current())) {
      harness.switchAgent(target.endpoint(), target.harnessSessionId(), agentId);
    }
    return new AgentSelection(agentId, selection.agents());
  }

  @Transactional(readOnly = true)
  public List<AgentForm> forms(UUID workspaceId, UUID sessionId) {
    var target = target(workspaceId, sessionId);
    return harness.forms(target.endpoint(), target.harnessSessionId());
  }

  @Transactional
  public void replyForm(
      UUID workspaceId, UUID sessionId, String formId, Map<String, Object> answer) {
    sessionForUpdate(workspaceId, sessionId);
    var target = target(workspaceId, sessionId);
    harness.replyForm(target.endpoint(), target.harnessSessionId(), formId, answer);
  }

  @Transactional
  public void cancelForm(UUID workspaceId, UUID sessionId, String formId) {
    sessionForUpdate(workspaceId, sessionId);
    var target = target(workspaceId, sessionId);
    harness.cancelForm(target.endpoint(), target.harnessSessionId(), formId);
  }

  /** With the workspace's chosen model, so a change of model applies from the next message. */
  @Transactional
  public void send(UUID workspaceId, UUID sessionId, String text) {
    var session = sessionForUpdate(workspaceId, sessionId);
    harness.prompt(
        endpoint(workspaceId),
        session.getPath(),
        session.getHarnessSessionId(),
        text,
        model(workspaceId));
  }

  @Transactional
  public void edit(UUID workspaceId, UUID sessionId, String messageId, String text) {
    var session = sessionForUpdate(workspaceId, sessionId);
    var endpoint = endpoint(workspaceId);
    var message =
        harness.messages(endpoint, session.getPath(), session.getHarnessSessionId()).stream()
            .filter(m -> m.id().equals(messageId))
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no message " + messageId));
    if (!"user".equals(message.role())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only user messages can be edited");
    }
    harness.editMessage(
        endpoint,
        session.getPath(),
        session.getHarnessSessionId(),
        messageId,
        text,
        model(workspaceId));
  }

  private Harness.ModelRef model(UUID workspaceId) {
    var workspace = workspaces.findById(workspaceId).orElseThrow();
    return workspace.getModel() == null
        ? new Harness.ModelRef(
            workspace.getModelProvider(),
            harness.models(endpoint(workspaceId), workspace.getModelProvider()).defaultModel())
        : new Harness.ModelRef(workspace.getModelProvider(), workspace.getModel());
  }

  @Transactional
  public void abort(UUID workspaceId, UUID sessionId) {
    var session = sessionForUpdate(workspaceId, sessionId);
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

  private WorkspaceSessionEntity sessionForUpdate(UUID workspaceId, UUID sessionId) {
    var session =
        sessions
            .lockByIdAndWorkspaceId(sessionId, workspaceId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the workspace has no session " + sessionId));
    return session;
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
