package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.ClusterLocks;
import io.github.kalpak44.workspacedai.api.harness.*;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.session.*;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Immutable dispatch snapshots; only completed, attributable harness steps become measurements. */
@Service
public class AiUsage {
  private final JdbcTemplate jdbc;
  private final WorkspaceRepository workspaces;
  private final WorkspaceSessionRepository sessions;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final ClusterLocks locks;

  public record Total(
      UUID workspaceId,
      String workspaceName,
      UUID connectorId,
      String provider,
      String model,
      long steps,
      java.math.BigDecimal input,
      java.math.BigDecimal output,
      java.math.BigDecimal reasoning,
      java.math.BigDecimal cacheRead,
      java.math.BigDecimal cacheWrite) {}

  private record Dispatch(
      UUID id, String provider, String anchor, boolean after, boolean accepted) {}

  public AiUsage(
      JdbcTemplate jdbc,
      WorkspaceRepository workspaces,
      WorkspaceSessionRepository sessions,
      WorkspaceRuntime runtime,
      Harness harness,
      ClusterLocks locks) {
    this.jdbc = jdbc;
    this.workspaces = workspaces;
    this.sessions = sessions;
    this.runtime = runtime;
    this.harness = harness;
    this.locks = locks;
  }

  /** Called inside the routing lease, before any prompt/model/form request. */
  public String prompt(WorkspaceSessionEntity session) {
    collect(session);
    var id = UUID.randomUUID();
    String input = "msg_" + id.toString().replace("-", "");
    save(session, id, input, false);
    return input;
  }

  /** Form continuation has no user input ID: fence it after the existing message history. */
  public UUID form(WorkspaceSessionEntity session) {
    var endpoint = runtime.harness(session.getWorkspaceId()).orElseThrow();
    var history = harness.usageHistory(endpoint, session.getHarnessSessionId());
    ingest(session, history);
    if (history.isEmpty()) return null;
    var id = UUID.randomUUID();
    save(session, id, history.getLast().id(), true);
    return id;
  }

  public void acceptForm(UUID dispatch) {
    if (dispatch != null) jdbc.update("UPDATE ai_dispatch SET accepted=true WHERE id=?", dispatch);
  }

  private void save(WorkspaceSessionEntity session, UUID id, String anchor, boolean after) {
    var w = workspaces.findById(session.getWorkspaceId()).orElseThrow();
    jdbc.update(
        "INSERT INTO ai_dispatch(id,owner_id,workspace_id,session_id,connector_id,provider,anchor,after_anchor,accepted,provider_revision) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        w.getOwnerId(),
        w.getId(),
        session.getId(),
        w.getAiConnectorId(),
        w.getModelProvider(),
        anchor,
        after,
        !after,
        w.getProviderChange().revision);
  }

  public void collect(WorkspaceSessionEntity session) {
    runtime
        .harness(session.getWorkspaceId())
        .ifPresent(
            endpoint ->
                ingest(session, harness.usageHistory(endpoint, session.getHarnessSessionId())));
  }

  void ingest(WorkspaceSessionEntity session, List<UsageMessage> history) {
    var dispatches =
        jdbc.query(
            "SELECT id,provider,anchor,after_anchor,accepted FROM ai_dispatch WHERE session_id=? ORDER BY created_at,id",
            (rs, n) ->
                new Dispatch(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    rs.getBoolean(5)),
            session.getId());
    var inputs = new HashMap<String, Dispatch>();
    var boundaries = new HashMap<String, Dispatch>();
    for (var d : dispatches) (d.after ? boundaries : inputs).put(d.anchor, d);
    Dispatch active = null;
    for (var m : history) {
      if ("user".equals(m.type())) active = inputs.get(m.id());
      if ("assistant".equals(m.type()) && m.id() != null && m.id().length() <= 100) {
        if (active != null && active.provider.equals(m.provider())) {
          jdbc.update(
              """
              INSERT INTO ai_step_attribution(session_id,message_id,dispatch_id)
              SELECT ?,?,id FROM ai_dispatch WHERE id=? AND NOT closed
              ON CONFLICT (session_id,message_id) DO NOTHING
              """,
              session.getId(),
              m.id(),
              active.id);
        }
        if (m.model() != null
            && m.model().length() <= 300
            && m.completedAt() != null
            && m.tokens() != null) {
          var t = m.tokens();
          jdbc.update(
              """
              INSERT INTO ai_usage(session_id,message_id,dispatch_id,model,completed_at,input_tokens,output_tokens,reasoning_tokens,cache_read_tokens,cache_write_tokens)
              SELECT a.session_id,a.message_id,a.dispatch_id,?,?,?,?,?,?,?
              FROM ai_step_attribution a JOIN ai_dispatch d ON d.id=a.dispatch_id
              WHERE a.session_id=? AND a.message_id=? AND d.provider=?
              ON CONFLICT (session_id,message_id) DO NOTHING
              """,
              m.model(),
              Timestamp.from(Instant.ofEpochMilli(m.completedAt())),
              t.input(),
              t.output(),
              t.reasoning(),
              t.cacheRead(),
              t.cacheWrite(),
              session.getId(),
              m.id(),
              m.provider());
        }
      }
      if (boundaries.containsKey(m.id()))
        active = boundaries.get(m.id()).accepted ? boundaries.get(m.id()) : null;
    }
  }

  /** Before changing credentials, seal old dispatches against automatic/local resumption. */
  public void fence(UUID workspace) {
    var deadline = Instant.now().plusSeconds(60);
    for (var session : sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspace)) {
      if (Instant.now().isAfter(deadline)) break;
      try {
        collect(session);
      } catch (RuntimeException unavailable) {
        // An unavailable old runtime cannot supply trustworthy new measurements.
      }
    }
    jdbc.update("UPDATE ai_dispatch SET closed=true WHERE workspace_id=?", workspace);
  }

  /** Independent of browser streams; missed completions are recovered from durable history. */
  @Scheduled(
      fixedDelayString = "${workspaced.ai.usage-collect-ms:30000}",
      initialDelayString = "${workspaced.ai.usage-collect-ms:30000}")
  public void reconcile() {
    for (var session :
        sessions.findAllById(
            jdbc.queryForList("SELECT DISTINCT session_id FROM ai_dispatch", UUID.class))) {
      try {
        locks.run("ai-usage-" + session.getId(), () -> collect(session));
      } catch (RuntimeException e) {
        LoggerFactory.getLogger(AiUsage.class)
            .debug("Usage collection deferred for session {}", session.getId());
      }
    }
  }

  /** Owner-only ledger: no collaborator identifiers, prompt contents, or credentials. */
  public List<Total> totals(UUID owner) {
    return jdbc.query(
        """
        SELECT d.workspace_id,d.connector_id,d.provider,u.model,count(*),
          sum(u.input_tokens),sum(u.output_tokens),sum(u.reasoning_tokens),sum(u.cache_read_tokens),sum(u.cache_write_tokens),coalesce(w.name,'Deleted workspace')
        FROM ai_usage u JOIN ai_dispatch d ON d.id=u.dispatch_id LEFT JOIN workspace w ON w.id=d.workspace_id AND w.owner_id=d.owner_id WHERE d.owner_id=?
        GROUP BY d.workspace_id,d.connector_id,d.provider,u.model,w.name
        ORDER BY d.workspace_id,d.connector_id,d.provider,u.model
        """,
        (rs, n) ->
            new Total(
                rs.getObject(1, UUID.class),
                rs.getString(11),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                rs.getBigDecimal(6),
                rs.getBigDecimal(7),
                rs.getBigDecimal(8),
                rs.getBigDecimal(9),
                rs.getBigDecimal(10)),
        owner);
  }
}
