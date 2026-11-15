package io.github.kalpak44.workspacedai.api.billing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers as Stripe's REST API does for the calls billing makes, keeping sessions in memory, so the
 * client is tested against the real request and response shapes without an account.
 */
class FakeStripe implements AutoCloseable {

  static final String SECRET_KEY = "rk_test_fake";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final AtomicInteger ids = new AtomicInteger();
  final Map<String, ObjectNode> sessions = new ConcurrentHashMap<>();
  final List<Map<String, String>> customers = new CopyOnWriteArrayList<>();
  final List<Map<String, String>> checkouts = new CopyOnWriteArrayList<>();
  final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
  volatile int status = 0;
  private volatile String lastSession;

  FakeStripe() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/v1/customers", this::customers);
    server.createContext("/v1/checkout/sessions", this::sessions);
    server.createContext("/v1/invoices", this::invoices);
    server.start();
  }

  URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort());
  }

  /** Completes the session as a card payment does, with Stripe issuing its invoice. */
  void pay(String sessionId) {
    var session = sessions.get(sessionId);
    session.put("status", "complete");
    session.put("payment_status", "paid");
    session.put("invoice", "in_" + sessionId);
  }

  void expire(String sessionId) {
    sessions.get(sessionId).put("status", "expired");
  }

  String lastSessionId() {
    return lastSession;
  }

  private void customers(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    customers.add(form(exchange));
    answer(exchange, 200, "{\"id\":\"cus_" + ids.incrementAndGet() + "\"}");
  }

  private void sessions(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    if (exchange.getRequestMethod().equals("POST")) {
      var form = form(exchange);
      checkouts.add(form);
      idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
      var id = "cs_" + ids.incrementAndGet();
      var session = JSON.createObjectNode();
      session.put("id", id);
      session.put("status", "open");
      session.put("payment_status", "unpaid");
      session.put(
          "amount_total", Long.parseLong(form.get("line_items[0][price_data][unit_amount]")));
      session.put("currency", form.get("line_items[0][price_data][currency]"));
      session.put("url", "https://checkout.stripe.test/" + id);
      session.putNull("invoice");
      sessions.put(id, session);
      lastSession = id;
      answer(exchange, 200, JSON.writeValueAsString(session));
      return;
    }
    var id = exchange.getRequestURI().getPath().substring("/v1/checkout/sessions/".length());
    var session = sessions.get(id);
    if (session == null) {
      answer(exchange, 404, "{\"error\":{\"message\":\"No such checkout.session\"}}");
      return;
    }
    var body = session.deepCopy();
    var expand = exchange.getRequestURI().getQuery();
    if (body.path("invoice").isString() && expand != null && expand.contains("invoice")) {
      var invoice = body.putObject("invoice");
      invoice.put("id", session.path("invoice").asString());
      invoice.put("number", "WSAI-" + id);
    }
    answer(exchange, 200, JSON.writeValueAsString(body));
  }

  private void invoices(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    var id = exchange.getRequestURI().getPath().substring("/v1/invoices/".length());
    var issued = sessions.values().stream().anyMatch(s -> id.equals(s.path("invoice").asString()));
    if (!issued) {
      answer(exchange, 404, "{\"error\":{\"message\":\"No such invoice\"}}");
      return;
    }
    answer(
        exchange,
        200,
        "{\"id\":\"" + id + "\",\"invoice_pdf\":\"https://pay.stripe.test/" + id + "/pdf\"}");
  }

  /** Stripe answers 401 to a wrong key, and the test-set status to everything once it is set. */
  private boolean refused(HttpExchange exchange) throws IOException {
    if (!("Bearer " + SECRET_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      answer(exchange, 401, "{\"error\":{\"message\":\"Invalid API Key provided\"}}");
      return true;
    }
    if (status != 0) {
      answer(exchange, status, "{\"error\":{\"message\":\"refused by the test\"}}");
      return true;
    }
    return false;
  }

  private static Map<String, String> form(HttpExchange exchange) throws IOException {
    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    var form = new LinkedHashMap<String, String>();
    for (var pair : body.split("&")) {
      var parts = pair.split("=", 2);
      form.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
    }
    return form;
  }

  private static void answer(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
