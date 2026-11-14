package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Answers the VM manager's ready (200) and heartbeat (204) routes under /api/v1, so no test needs
 * the real one. A VM in {@link #gone} answers 404; setting {@link #status} makes every route answer
 * with it instead.
 */
public class FakeVmManager implements AutoCloseable {

  private final HttpServer server;
  public final List<String> reports = new CopyOnWriteArrayList<>();
  public final Set<UUID> gone = ConcurrentHashMap.newKeySet();
  public volatile int status = 0;

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
    // /api/v1/vms/{id}/{report}
    var parts = exchange.getRequestURI().getPath().split("/");
    var id = UUID.fromString(parts[4]);
    var report = parts[5];
    int code;
    if (status != 0) {
      code = status;
    } else if (!exchange.getRequestMethod().equals("POST")) {
      code = 405;
    } else if (gone.contains(id)) {
      code = 404;
    } else {
      reports.add(report + " " + id);
      code = report.equals("ready") ? 200 : 204;
    }
    var body = code == 200 ? "{}".getBytes() : new byte[0];
    // HttpServer takes -1 as "no body", which is what a 204 must be.
    exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
    exchange.getResponseBody().write(body);
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
