package io.github.kalpak44.workspacedai.agenthub.tunnel;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
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
 * The API's socket upgrades under {@code /api/v1/internal/vms/{vmId}/harness/**}, such as a
 * terminal's, relayed through one of the VM's tunnel workers. The upgrade completes only once the
 * supervisor has opened the harness's socket, so a refusal reaches the API as a status rather than
 * as a socket that closes at once.
 */
@Component
public class HarnessSocketHandler extends AbstractWebSocketHandler implements HandshakeInterceptor {

  static final String PROTOCOL = HarnessSocketHandler.class.getName() + ".protocol";
  private static final String RELAY = HarnessSocketHandler.class.getName() + ".relay";
  // The supervisor dials a socket on its own loopback, so this only waits out a stuck one.
  private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(30);

  private final WorkerRegistry workers;
  private final JsonMapper json;

  public HarnessSocketHandler(WorkerRegistry workers, JsonMapper json) {
    this.workers = workers;
    this.json = json;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    var servlet = Handshakes.servlet(request);
    try {
      var target = HarnessTarget.of(servlet);
      var head =
          json.createObjectNode()
              .put("type", target.web() ? "web-ws" : "ws")
              .put("path", target.path())
              .put("web", target.web());
      head.set("headers", json.valueToTree(ProxyHeaders.ofHandshake(servlet)));
      if (target.web()) {
        ((tools.jackson.databind.node.ObjectNode) head.get("headers"))
            .putArray("Host")
            .add(servlet.getHeader("Host"));
        if (servlet.getHeader("Authorization") != null) {
          ((tools.jackson.databind.node.ObjectNode) head.get("headers"))
              .putArray("Authorization")
              .add(servlet.getHeader("Authorization"));
        }
      }
      var relay = new SocketRelay(json);
      relay.attach(workers.start(target.vmId(), relay, head));
      String protocol;
      try {
        protocol = relay.awaitOpen(OPEN_TIMEOUT);
      } catch (TunnelException e) {
        relay.abandon();
        throw e;
      }
      attributes.put(RELAY, relay);
      // afterHandshake is not handed the attributes, and the handshake handler reads it here.
      servlet.setAttribute(RELAY, relay);
      servlet.setAttribute(PROTOCOL, protocol);
      return true;
    } catch (TunnelException e) {
      Handshakes.refuse(request, response, json, e.status(), e.getMessage());
      return false;
    }
  }

  /** An upgrade that failed after the worker was taken would otherwise hold it for good. */
  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {
    var upgraded =
        exception == null
            && response instanceof ServletServerHttpResponse servlet
            && servlet.getServletResponse().getStatus() == 101;
    if (!upgraded && Handshakes.servlet(request).getAttribute(RELAY) instanceof SocketRelay relay) {
      relay.abandon();
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    Handshakes.neverIdle(session);
    relay(session).bind(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    relay(session).fromApi(message);
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    relay(session).fromApi(message);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    relay(session).apiClosed(status);
  }

  /** Parts as they arrive, so a large message costs only its own size, never a fixed buffer. */
  @Override
  public boolean supportsPartialMessages() {
    return true;
  }

  private static SocketRelay relay(WebSocketSession session) {
    return (SocketRelay) session.getAttributes().get(RELAY);
  }
}
