package io.github.kalpak44.workspacedai.agenthub.tunnel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API's plain HTTP calls to a VM's harness, each carried by one of the VM's tunnel workers.
 * Socket upgrades on the same paths go to {@link HarnessSocketHandler} instead. Like every internal
 * route, {@code InternalRoutesFilter} refuses it through the ingress.
 *
 * <p>The request thread blocks for the whole exchange, which is cheap on a virtual thread and is
 * what keeps an event stream open as long as it runs: there is no async request to time out.
 */
@RestController
public class HarnessProxyController {

  private static final Logger log = LoggerFactory.getLogger(HarnessProxyController.class);

  // Under the 32 KiB a WebSocket library reads per message by default.
  private static final int CHUNK = 16 * 1024;
  // A harness that never answers would otherwise hold its worker for good.
  private static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(10);

  private final WorkerRegistry workers;
  private final JsonMapper json;

  public HarnessProxyController(WorkerRegistry workers, JsonMapper json) {
    this.workers = workers;
    this.json = json;
  }

  @RequestMapping(
      path = "/internal/vms/{vmId}/harness/**",
      method = {
        RequestMethod.GET,
        RequestMethod.HEAD,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE,
        RequestMethod.OPTIONS,
        RequestMethod.TRACE
      })
  public void proxy(
      @PathVariable String vmId, HttpServletRequest request, HttpServletResponse response) {
    var target = HarnessTarget.of(request, vmId);
    var head =
        json.createObjectNode()
            .put("type", "http")
            .put("method", request.getMethod())
            .put("path", target.path());
    head.set("headers", json.valueToTree(ProxyHeaders.ofRequest(request)));
    var exchange = new Exchange();
    var worker = workers.start(target.vmId(), exchange, head);
    try {
      send(request, worker);
      answer(exchange, response);
    } finally {
      exchange.finish();
      worker.close(CloseStatus.NORMAL);
    }
  }

  private void send(HttpServletRequest request, Worker worker) {
    try (var body = request.getInputStream()) {
      var buffer = new byte[CHUNK];
      int read;
      while ((read = body.read(buffer)) >= 0) {
        if (read > 0) {
          worker.send(Arrays.copyOf(buffer, read));
        }
      }
      worker.send(json.createObjectNode().put("type", "end"));
    } catch (IOException e) {
      throw TunnelException.dropped("the request could not be passed to the harness");
    }
  }

  private void answer(Exchange exchange, HttpServletResponse response) {
    var message =
        switch (exchange.next(ANSWER_TIMEOUT)) {
          case JsonNode head when "response".equals(type(head)) -> head;
          case JsonNode error when "error".equals(type(error)) -> throw TunnelException.of(error);
          case null ->
              throw exchange.socketClosed
                  ? TunnelException.dropped("the harness connection closed before it answered")
                  : new TunnelException(HttpStatus.GATEWAY_TIMEOUT, "the harness did not answer");
          default -> throw TunnelException.dropped("the harness answered out of turn");
        };
    response.setStatus(message.path("status").asInt(502));
    for (var header : message.path("headers").properties()) {
      if (ProxyHeaders.dropped(header.getKey())) {
        continue;
      }
      for (var value : header.getValue()) {
        response.addHeader(header.getKey(), value.asString());
      }
    }
    try {
      // Flushed at once and after every frame, so an event stream is never held back.
      response.flushBuffer();
      var out = response.getOutputStream();
      while (exchange.next(null) instanceof byte[] chunk) {
        out.write(chunk);
        out.flush();
      }
    } catch (IOException e) {
      // The API has gone; closing the worker tells the harness.
      log.debug("Harness call abandoned by its caller: {}", e.getMessage());
    }
  }

  private static String type(JsonNode message) {
    return message.path("type").asString("");
  }

  /**
   * What the worker sends, queued for the request thread: control messages as JSON, payload as byte
   * arrays, and {@link #CLOSED} when the socket goes. The bound holds a fast harness back to the
   * pace of a slow caller, by holding up the worker socket's reads.
   */
  private static final class Exchange implements WorkerListener {

    private static final Object CLOSED = new Object();

    private final BlockingQueue<Object> received = new LinkedBlockingQueue<>(64);
    private volatile boolean done;
    private boolean socketClosed;

    @Override
    public void control(JsonNode message) {
      put(message);
    }

    @Override
    public void payload(ByteBuffer part, boolean last) {
      var bytes = new byte[part.remaining()];
      part.get(bytes);
      put(bytes);
    }

    @Override
    public void closed(CloseStatus status) {
      put(CLOSED);
    }

    /**
     * @param timeout null to wait for as long as it takes
     * @return null when nothing came in time, or the exchange is over
     */
    Object next(Duration timeout) {
      try {
        var next =
            timeout == null
                ? received.take()
                : received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        socketClosed = next == CLOSED;
        return socketClosed ? null : next;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

    /** Stops queueing, so a socket thread waiting for room is let go. */
    void finish() {
      done = true;
      received.clear();
    }

    // Gives up once the request thread has stopped reading, so a full queue cannot hang the socket.
    private void put(Object item) {
      try {
        while (!done && !received.offer(item, 1, TimeUnit.SECONDS)) {
          // Still reading; wait for room.
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
