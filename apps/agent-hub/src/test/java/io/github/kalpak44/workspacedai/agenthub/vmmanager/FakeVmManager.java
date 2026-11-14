package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Answers the VM manager's ready and heartbeat routes (200 with the VM) and its harness route under
 * /api/v1, so no test needs the real one. A VM in {@link #gone} answers 404; setting {@link
 * #status} makes every route answer with it instead, and {@link #body} replaces every 200 body.
 */
public class FakeVmManager implements AutoCloseable {

  public static final String HARNESS =
      "{\"image\":\"ghcr.io/kalpak44/workspaced-ai-harness:1\","
          + "\"environment\":{\"OPENCODE_SERVER_PASSWORD\":\"harness-pw\"}}";

  private final HttpServer server;
  public final List<String> reports = new CopyOnWriteArrayList<>();
  public final Set<UUID> gone = ConcurrentHashMap.newKeySet();
  public volatile int status = 0;
  public volatile String vmStatus = "READY";
  public volatile String body;

  public FakeVmManager() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/api/v1/vms/", this::vms);
    server.start();
  }

  private void vms(HttpExchange exchange) throws IOException {
    // /api/v1/vms/{id}/{route}
    var parts = exchange.getRequestURI().getPath().split("/");
    var id = UUID.fromString(parts[4]);
    var route = parts[5];
    var method = route.equals("harness") ? "GET" : "POST";
    int code;
    if (status != 0) {
      code = status;
    } else if (!exchange.getRequestMethod().equals(method)) {
      code = 405;
    } else if (gone.contains(id)) {
      code = 404;
    } else {
      reports.add(route + " " + id);
      code = 200;
    }
    var answer =
        body != null
            ? body
            : route.equals("harness")
                ? HARNESS
                : "{\"id\":\"" + id + "\",\"status\":\"" + vmStatus + "\"}";
    var bytes = code == 200 ? answer.getBytes(StandardCharsets.UTF_8) : new byte[0];
    // HttpServer takes -1 as "no body".
    exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  public URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort() + "/api/v1");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
