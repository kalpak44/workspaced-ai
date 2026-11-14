package io.github.kalpak44.workspacedai.agenthub.tunnel;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One socket a VM's supervisor holds open to the hub, which carries exactly one proxied exchange
 * and is then closed.
 */
final class Worker {

  private final UUID vmId;
  private final WebSocketSession session;
  private final JsonMapper json;
  // A socket refuses a second send while one is in flight, and both ends of a relay send.
  private final ReentrantLock sending = new ReentrantLock();
  // Only the socket's own thread touches it, one message at a time.
  private final StringBuilder text = new StringBuilder();
  private volatile WorkerListener listener;

  Worker(UUID vmId, WebSocketSession session, JsonMapper json) {
    this.vmId = vmId;
    this.session = session;
    this.json = json;
  }

  UUID vmId() {
    return vmId;
  }

  /** Hands the worker to an exchange, then sends the exchange's head. */
  void start(WorkerListener listener, JsonNode head) throws IOException {
    this.listener = listener;
    send(head);
  }

  void send(JsonNode message) throws IOException {
    send(new TextMessage(json.writeValueAsString(message)));
  }

  void send(byte[] payload) throws IOException {
    send(new BinaryMessage(payload));
  }

  private void send(WebSocketMessage<?> message) throws IOException {
    sending.lock();
    try {
      session.sendMessage(message);
    } finally {
      sending.unlock();
    }
  }

  /** Ends the exchange; closing a socket already closed does nothing. */
  void close(CloseStatus status) {
    try {
      session.close(status);
    } catch (IOException e) {
      // Already gone, which is what closing wanted.
    }
  }

  void received(TextMessage part) {
    text.append(part.getPayload());
    if (!part.isLast()) {
      return;
    }
    var message = text.toString();
    text.setLength(0);
    var current = listener;
    if (current == null) {
      // An idle worker has nothing to say, so the supervisor is not speaking this protocol.
      close(CloseStatus.PROTOCOL_ERROR);
      return;
    }
    try {
      current.control(json.readTree(message));
    } catch (JacksonException e) {
      close(CloseStatus.BAD_DATA);
    }
  }

  void received(BinaryMessage part) {
    var current = listener;
    if (current == null) {
      close(CloseStatus.PROTOCOL_ERROR);
      return;
    }
    current.payload(part.getPayload(), part.isLast());
  }

  void closed(CloseStatus status) {
    var current = listener;
    if (current != null) {
      current.closed(status);
    }
  }
}
