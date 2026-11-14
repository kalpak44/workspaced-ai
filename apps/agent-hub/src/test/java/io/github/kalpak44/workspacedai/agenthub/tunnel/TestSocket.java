package io.github.kalpak44.workspacedai.agenthub.tunnel;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One end of a socket as a test drives it: a supervisor's worker, or the API's relayed socket.
 * Everything received is queued whole, text as {@link String}, binary as {@code byte[]}, and the
 * close as {@link Closed}, for the test to take in order.
 */
final class TestSocket implements WebSocket.Listener {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  record Closed(int code, String reason) {}

  private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
  private final StringBuilder text = new StringBuilder();
  private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
  WebSocket socket;

  static CompletableFuture<TestSocket> connect(
      HttpClient http, URI uri, Map<String, String> headers, String... protocols) {
    var listener = new TestSocket();
    var builder = http.newWebSocketBuilder();
    headers.forEach(builder::header);
    if (protocols.length > 0) {
      builder.subprotocols(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length));
    }
    return builder
        .buildAsync(uri, listener)
        .thenApply(
            socket -> {
              listener.socket = socket;
              return listener;
            });
  }

  Object next() throws InterruptedException {
    var next = received.poll(5, TimeUnit.SECONDS);
    if (next == null) {
      throw new AssertionError("nothing received within 5 s");
    }
    return next;
  }

  /** The close, when it is the next thing received within the wait; anything else is dropped. */
  Closed closedWithin(Duration wait) throws InterruptedException {
    var next = received.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    return next instanceof Closed closed ? closed : null;
  }

  JsonNode nextJson() throws InterruptedException {
    return JSON.readTree((String) next());
  }

  byte[] nextBytes() throws InterruptedException {
    return (byte[]) next();
  }

  Closed nextClose() throws InterruptedException {
    return (Closed) next();
  }

  void send(String json) {
    socket.sendText(json, true).join();
  }

  void send(byte[] bytes) {
    socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
  }

  /** A relayed message as the supervisor frames it: type byte, then the message. */
  void relay(byte type, byte[] bytes) {
    var framed = new byte[bytes.length + 1];
    framed[0] = type;
    System.arraycopy(bytes, 0, framed, 1, bytes.length);
    send(framed);
  }

  void relay(String text) {
    relay((byte) 1, text.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    text.append(data);
    if (last) {
      received.add(text.toString());
      text.setLength(0);
    }
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
    var bytes = new byte[data.remaining()];
    data.get(bytes);
    binary.writeBytes(bytes);
    if (last) {
      received.add(binary.toByteArray());
      binary.reset();
    }
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    received.add(new Closed(statusCode, reason));
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    received.add(new Closed(-1, String.valueOf(error.getMessage())));
  }
}
