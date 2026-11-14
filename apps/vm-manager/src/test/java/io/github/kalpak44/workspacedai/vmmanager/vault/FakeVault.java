package io.github.kalpak44.workspacedai.vmmanager.vault;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers as Vault does, so no test calls the real one: Kubernetes sign-in for one service account
 * token, and a KV version 2 engine at {@code secret/} that keeps entries in memory and honours
 * check-and-set. Every other token is refused with 403, as Vault refuses an expired one.
 */
public class FakeVault implements AutoCloseable {

  public static final String JWT = "service-account-jwt";
  public static final String ROOT_TOKEN = "root-t0ken";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  public final Map<String, Map<String, String>> entries = new ConcurrentHashMap<>();
  public final Map<String, Integer> versions = new ConcurrentHashMap<>();
  public final Map<String, Integer> maxVersions = new ConcurrentHashMap<>();
  public final List<String> logins = new CopyOnWriteArrayList<>();
  public final Set<String> tokens = ConcurrentHashMap.newKeySet();
  private final AtomicInteger issued = new AtomicInteger();

  /** Runs before each data write is applied, to land a competing write in between. */
  public volatile Runnable beforeWrite = () -> {};

  public FakeVault() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    tokens.add(ROOT_TOKEN);
    server.createContext("/v1/auth/kubernetes/login", this::login);
    server.createContext("/v1/secret/", this::kv);
    server.start();
  }

  public String url() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private void login(HttpExchange exchange) throws IOException {
    var body = JSON.readTree(exchange.getRequestBody());
    logins.add(body.path("role").asString());
    if (!JWT.equals(body.path("jwt").asString())) {
      answer(exchange, 403, "{\"errors\":[\"permission denied\"]}");
      return;
    }
    var token = "s.token-" + issued.incrementAndGet();
    tokens.add(token);
    answer(
        exchange, 200, "{\"auth\":{\"client_token\":\"" + token + "\",\"lease_duration\":3600}}");
  }

  private void kv(HttpExchange exchange) throws IOException {
    if (!tokens.contains(exchange.getRequestHeaders().getFirst("X-Vault-Token"))) {
      answer(exchange, 403, "{\"errors\":[\"permission denied\"]}");
      return;
    }
    var path = exchange.getRequestURI().getPath().substring("/v1/secret/".length());
    var kind = path.substring(0, path.indexOf('/'));
    var key = path.substring(kind.length() + 1);
    switch (exchange.getRequestMethod() + " " + kind) {
      case "GET data" -> {
        var entry = entries.get(key);
        if (entry == null) {
          answer(exchange, 404, "{\"errors\":[]}");
          return;
        }
        var body = JSON.createObjectNode();
        var data = body.putObject("data");
        var values = data.putObject("data");
        entry.forEach(values::put);
        data.putObject("metadata").put("version", versions.get(key));
        answer(exchange, 200, body.toString());
      }
      case "POST data" -> write(exchange, key);
      case "POST metadata" -> {
        maxVersions.put(key, JSON.readTree(exchange.getRequestBody()).path("max_versions").asInt());
        answer(exchange, 204, "");
      }
      case "DELETE metadata" -> {
        entries.remove(key);
        versions.remove(key);
        maxVersions.remove(key);
        answer(exchange, 204, "");
      }
      default -> answer(exchange, 405, "{\"errors\":[]}");
    }
  }

  private synchronized void write(HttpExchange exchange, String key) throws IOException {
    var body = JSON.readTree(exchange.getRequestBody());
    beforeWrite.run();
    var current = versions.getOrDefault(key, 0);
    if (body.path("options").path("cas").asInt(-1) != current) {
      answer(
          exchange,
          400,
          "{\"errors\":[\"check-and-set parameter did not match the current version\"]}");
      return;
    }
    var data = new HashMap<String, String>();
    body.path("data").properties().forEach(e -> data.put(e.getKey(), e.getValue().asString()));
    put(key, data);
    answer(exchange, 200, "{\"data\":{\"version\":" + (current + 1) + "}}");
  }

  /** Writes an entry as another client would, moving its version on. */
  public synchronized void put(String key, Map<String, String> data) {
    entries.put(key, Map.copyOf(data));
    versions.merge(key, 1, Integer::sum);
  }

  private static void answer(HttpExchange exchange, int code, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
