package io.github.kalpak44.workspacedai.agenthub.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One of the API's sockets relayed through a worker to the harness. On the worker every message is
 * a binary frame whose first byte says whether it was text ({@code 0x01}) or binary ({@code 0x02});
 * the only text frame after {@code open} is {@code end}, which closes the relayed socket.
 */
final class SocketRelay implements WorkerListener {

  private static final byte TEXT = 0x01;
  private static final byte BINARY = 0x02;
  // Terminal traffic is keystrokes and screenfuls; anything this large is not a terminal.
  private static final int MAX_MESSAGE = 16 * 1024 * 1024;

  private final JsonMapper json;
  private final CompletableFuture<String> opened = new CompletableFuture<>();
  private final AtomicBoolean ended = new AtomicBoolean();
  // The API's socket does not exist until the upgrade completes, which is after open: what the
  // harness sends meanwhile waits in pending.
  private final ReentrantLock apiLock = new ReentrantLock();
  private final List<WebSocketMessage<?>> pending = new ArrayList<>();
  private WebSocketSession api;
  private CloseStatus pendingClose;
  private final AtomicReference<Worker> worker = new AtomicReference<>();
  // Each is only touched by the thread of the socket it reassembles.
  private final ByteArrayOutputStream fromWorker = new ByteArrayOutputStream();
  private final ByteArrayOutputStream fromApi = new ByteArrayOutputStream();

  SocketRelay(JsonMapper json) {
    this.json = json;
  }

  void attach(Worker worker) {
    this.worker.set(worker);
  }

  /**
   * Waits for the supervisor to have dialled the harness's socket.
   *
   * @return the subprotocol the harness chose, empty for none
   * @throws TunnelException the harness refused, the worker closed, or neither happened in time
   */
  String awaitOpen(Duration timeout) {
    try {
      return opened.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof TunnelException refused
          ? refused
          : TunnelException.dropped("the harness socket could not be opened");
    } catch (TimeoutException e) {
      throw new TunnelException(HttpStatus.GATEWAY_TIMEOUT, "the harness socket did not open");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw TunnelException.dropped("interrupted opening the harness socket");
    }
  }

  /** The API's socket is established: hands it everything the harness has sent so far. */
  void bind(WebSocketSession session) {
    apiLock.lock();
    try {
      api = session;
      for (var message : pending) {
        sendToApi(message);
      }
      pending.clear();
      if (pendingClose != null) {
        closeApi(pendingClose);
      }
    } finally {
      apiLock.unlock();
    }
  }

  /** Abandons the exchange before the API's socket exists. */
  void abandon() {
    ended.set(true);
    var current = worker.get();
    if (current != null) {
      current.close(CloseStatus.NORMAL);
    }
  }

  // --- From the worker ---------------------------------------------------------------------

  @Override
  public void control(JsonNode message) {
    switch (message.path("type").asString("")) {
      case "open" -> opened.complete(message.path("protocol").asString(""));
      case "error" -> {
        opened.completeExceptionally(TunnelException.of(message));
        toApi(CloseStatus.SERVER_ERROR.withReason("the harness socket failed"));
      }
      case "end" -> {
        ended.set(true);
        toApi(closeStatus(message.path("code").asInt(1000), message.path("reason").asString("")));
      }
      default -> {
        // Nothing else is sent after open; an unknown type is ignored rather than fatal.
      }
    }
  }

  @Override
  public void payload(ByteBuffer part, boolean last) {
    if (fromWorker.size() + part.remaining() > MAX_MESSAGE) {
      fromWorker.reset();
      toApi(CloseStatus.TOO_BIG_TO_PROCESS);
      return;
    }
    var bytes = new byte[part.remaining()];
    part.get(bytes);
    fromWorker.writeBytes(bytes);
    if (!last) {
      return;
    }
    var message = fromWorker.toByteArray();
    fromWorker.reset();
    if (message.length == 0) {
      return;
    }
    var rest = Arrays.copyOfRange(message, 1, message.length);
    switch (message[0]) {
      case TEXT -> toApi(new TextMessage(new String(rest, StandardCharsets.UTF_8)));
      case BINARY -> toApi(new BinaryMessage(rest));
      default -> toApi(CloseStatus.PROTOCOL_ERROR);
    }
  }

  /** The worker went without an {@code end}: the harness's socket is lost with it. */
  @Override
  public void closed(CloseStatus status) {
    opened.completeExceptionally(
        TunnelException.dropped("the harness connection closed before the socket opened"));
    if (ended.compareAndSet(false, true)) {
      toApi(CloseStatus.SERVER_ERROR.withReason("the harness connection closed"));
    }
  }

  private void toApi(WebSocketMessage<?> message) {
    apiLock.lock();
    try {
      if (api == null) {
        pending.add(message);
      } else {
        sendToApi(message);
      }
    } finally {
      apiLock.unlock();
    }
  }

  private void toApi(CloseStatus status) {
    apiLock.lock();
    try {
      if (api == null) {
        pendingClose = status;
      } else {
        closeApi(status);
      }
    } finally {
      apiLock.unlock();
    }
  }

  private void sendToApi(WebSocketMessage<?> message) {
    try {
      api.sendMessage(message);
    } catch (IOException e) {
      closeApi(CloseStatus.SERVER_ERROR);
    }
  }

  private void closeApi(CloseStatus status) {
    try {
      api.close(status);
    } catch (IOException e) {
      // Already gone.
    }
  }

  // --- From the API ------------------------------------------------------------------------

  void fromApi(TextMessage part) {
    fromApi(TEXT, part.getPayload().getBytes(StandardCharsets.UTF_8), part.isLast());
  }

  void fromApi(BinaryMessage part) {
    var bytes = new byte[part.getPayloadLength()];
    part.getPayload().get(bytes);
    fromApi(BINARY, bytes, part.isLast());
  }

  private void fromApi(byte type, byte[] bytes, boolean last) {
    if (fromApi.size() == 0) {
      fromApi.write(type);
    }
    if (fromApi.size() + bytes.length > MAX_MESSAGE) {
      fromApi.reset();
      toApi(CloseStatus.TOO_BIG_TO_PROCESS);
      return;
    }
    fromApi.writeBytes(bytes);
    if (!last) {
      return;
    }
    var message = fromApi.toByteArray();
    fromApi.reset();
    try {
      worker.get().send(message);
    } catch (IOException e) {
      toApi(CloseStatus.SERVER_ERROR.withReason("the harness connection closed"));
    }
  }

  /** The API closed its socket: tells the harness's side, then lets the worker go. */
  void apiClosed(CloseStatus status) {
    var current = worker.get();
    if (ended.compareAndSet(false, true)) {
      var end = closeStatus(status.getCode(), status.getReason() == null ? "" : status.getReason());
      try {
        current.send(
            json.createObjectNode()
                .put("type", "end")
                .put("code", end.getCode())
                .put("reason", end.getReason()));
      } catch (IOException e) {
        // The worker is gone too, which ends the exchange all the same.
      }
    }
    current.close(CloseStatus.NORMAL);
  }

  /**
   * A code the API's socket may be closed with; the reserved ones (no status, abnormal closure)
   * become a normal one, and a reason is cut to what a close frame holds.
   */
  static CloseStatus closeStatus(int code, String reason) {
    var sendable =
        code >= 1000 && code <= 1003
            || code >= 1007 && code <= 1011
            || code >= 3000 && code <= 4999;
    var cut = reason;
    while (cut.getBytes(StandardCharsets.UTF_8).length > 123) {
      cut = cut.substring(0, cut.length() - 1);
    }
    return new CloseStatus(sendable ? code : 1000, cut);
  }
}
