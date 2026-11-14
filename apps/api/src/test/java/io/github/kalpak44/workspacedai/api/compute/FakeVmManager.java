package io.github.kalpak44.workspacedai.api.compute;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers as the VM manager does, keeping VMs in memory, so the client is tested against the real
 * request and response shapes without the service. Setting {@link #status} makes every route answer
 * with it and {@link #detail} instead.
 */
class FakeVmManager implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static final String OPTIONS =
      """
      [{"serverType":"cx23","isDefault":true,"cores":2,"memoryGb":4.0,"diskGb":40,
        "cpuType":"shared","architecture":"x86","location":"nbg1",
        "monthlyNet":5.49,"monthlyGross":6.59},
       {"serverType":"cx33","isDefault":false,"cores":4,"memoryGb":8.0,"diskGb":80,
        "cpuType":"shared","architecture":"x86","location":"nbg1",
        "monthlyNet":8.49,"monthlyGross":10.19}]
      """;

  private final HttpServer server;
  final Map<UUID, ObjectNode> vms = new ConcurrentHashMap<>();
  final List<JsonNode> creates = new CopyOnWriteArrayList<>();
  final List<UUID> restarts = new CopyOnWriteArrayList<>();
  volatile int status = 0;
  volatile String detail = "";

  FakeVmManager() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/api/v1/vm-options", e -> answer(e, 200, OPTIONS));
    server.createContext("/api/v1/vms", this::vms);
    server.start();
  }

  private void vms(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    switch (exchange.getRequestMethod()) {
      case "GET" -> {
        if (path.endsWith("/events")) {
          events(exchange);
          return;
        }
        var query = exchange.getRequestURI().getQuery();
        var workspace = query == null ? null : query.replace("workspaceId=", "");
        var list = JSON.createArrayNode();
        vms.values().stream()
            .filter(vm -> workspace == null || vm.path("workspaceId").asString().equals(workspace))
            .forEach(list::add);
        answer(exchange, 200, list.toString());
      }
      case "POST" -> {
        if (path.endsWith("/restart")) {
          var id = UUID.fromString(path.split("/")[4]);
          restarts.add(id);
          var vm = vms.get(id);
          answer(exchange, vm == null ? 404 : 202, vm == null ? "{}" : vm.toString());
          return;
        }
        var request = JSON.readTree(exchange.getRequestBody());
        creates.add(request);
        var type = request.path("serverType").asString("cx23");
        if (!type.equals("cx23") && !type.equals("cx33")) {
          answer(
              exchange,
              400,
              "{\"detail\":\"server type " + type + " is not offered; choose one of cx23, cx33\"}");
          return;
        }
        var existing =
            vms.values().stream()
                .filter(vm -> vm.path("workspaceId").equals(request.path("workspaceId")))
                .findFirst();
        if (existing.isPresent()) {
          answer(exchange, 200, existing.get().toString());
          return;
        }
        var vm = JSON.createObjectNode();
        var id = UUID.randomUUID();
        vm.put("id", id.toString());
        vm.set("workspaceId", request.path("workspaceId"));
        vm.set("userId", request.path("userId"));
        vm.put("serverType", type);
        vm.put("serverId", 42);
        vm.put("status", "PROVISIONING");
        vm.put("ipv4", "203.0.113.10");
        vms.put(id, vm);
        answer(exchange, 201, vm.toString());
      }
      case "DELETE" -> {
        var id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        answer(exchange, vms.remove(id) == null ? 404 : 204, "");
      }
      default -> answer(exchange, 405, "{}");
    }
  }

  /** One status event per VM, as the stream opens with, then the stream ends. */
  private void events(HttpExchange exchange) throws IOException {
    var body = new StringBuilder(":keepalive\n\n");
    vms.values().forEach(vm -> body.append("event:status\ndata:").append(vm).append("\n\n"));
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    answer(exchange, 200, body.toString());
  }

  private void answer(HttpExchange exchange, int code, String body) throws IOException {
    var effective = status != 0 ? status : code;
    var text = status != 0 ? "{\"detail\":\"" + detail + "\"}" : body;
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(effective, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort() + "/api/v1");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
