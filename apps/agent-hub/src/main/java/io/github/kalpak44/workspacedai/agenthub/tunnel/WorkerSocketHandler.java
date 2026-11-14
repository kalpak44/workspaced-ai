package io.github.kalpak44.workspacedai.agenthub.tunnel;

import io.github.kalpak44.workspacedai.agenthub.agent.AgentService;
import io.github.kalpak44.workspacedai.agenthub.agent.SignatureRefusedException;
import io.github.kalpak44.workspacedai.agenthub.agent.SignedRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/agent/tunnel}: the worker sockets a VM's supervisor keeps open, each signed in
 * its handshake like any agent request and then waiting in {@link WorkerRegistry} for an exchange.
 */
@Component
public class WorkerSocketHandler extends AbstractWebSocketHandler implements HandshakeInterceptor {

  private static final Logger log = LoggerFactory.getLogger(WorkerSocketHandler.class);
  private static final String VM = "vmId";
  private static final String WORKER = "worker";

  private final AgentService agents;
  private final WorkerRegistry workers;
  private final JsonMapper json;

  public WorkerSocketHandler(AgentService agents, WorkerRegistry workers, JsonMapper json) {
    this.agents = agents;
    this.workers = workers;
    this.json = json;
  }

  /** Refused with 401 before the upgrade; the reason is logged, not answered, as for any route. */
  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    var servlet = Handshakes.servlet(request);
    try {
      var vmId =
          agents.connect(
              new SignedRequest(
                  servlet.getHeader("X-Agent-Vm"),
                  servlet.getHeader("X-Agent-Timestamp"),
                  servlet.getHeader("X-Agent-Signature"),
                  servlet.getMethod(),
                  servlet.getRequestURI(),
                  new byte[0]));
      attributes.put(VM, vmId);
      return true;
    } catch (SignatureRefusedException e) {
      log.info("Refused a tunnel worker: {}", e.getMessage());
      Handshakes.refuse(
          request, response, json, HttpStatus.UNAUTHORIZED, "request signature refused");
      return false;
    }
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {
    // Nothing is held until the socket is established.
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Handshakes.neverIdle(session);
    var worker = new Worker((UUID) session.getAttributes().get(VM), session, json);
    if (!workers.add(worker)) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("too many tunnel workers"));
      return;
    }
    session.getAttributes().put(WORKER, worker);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    if (session.getAttributes().get(WORKER) instanceof Worker worker) {
      worker.received(message);
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    if (session.getAttributes().get(WORKER) instanceof Worker worker) {
      worker.received(message);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    if (session.getAttributes().get(WORKER) instanceof Worker worker) {
      workers.remove(worker);
      worker.closed(status);
    }
  }

  /** Parts as they arrive, so no message is buffered whole, and an event stream is not held. */
  @Override
  public boolean supportsPartialMessages() {
    return true;
  }
}
