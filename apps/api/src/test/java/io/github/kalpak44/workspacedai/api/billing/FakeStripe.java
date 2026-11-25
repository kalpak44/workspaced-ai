package io.github.kalpak44.workspacedai.api.billing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
  final Map<String, ObjectNode> taxRates = new ConcurrentHashMap<>();
  final Map<String, ObjectNode> invoices = new ConcurrentHashMap<>();
  final Map<String, ObjectNode> charges = new ConcurrentHashMap<>();
  final List<String> detached = new CopyOnWriteArrayList<>();
  // What each keyed POST first answered, which Stripe answers again to a retry with the key.
  private final Map<String, String> answered = new ConcurrentHashMap<>();
  volatile int status = 0;
  volatile boolean declineCards;
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
    server.createContext("/v1/invoiceitems", this::invoiceItems);
    server.createContext("/v1/payment_methods", this::paymentMethods);
    server.createContext("/v1/tax_rates", this::taxRates);
    server.createContext("/v1/charges", this::charges);
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
    session.put("payment_intent", "pi_" + sessionId);
    // Stripe issues a Checkout payment's invoice after the charge, which then names no invoice.
    charges.put(
        "ch_" + sessionId,
        charge("ch_" + sessionId, session.path("amount_total").asLong(), null, "pi_" + sessionId));
  }

  /** The charge that paid a Checkout Session, or an invoice charged off session. */
  String chargeOf(String sessionOrInvoiceId) {
    return "ch_" + sessionOrInvoiceId;
  }

  /** Refunds the charge, adding to what earlier refunds of it returned. */
  void refund(String chargeId, long cents) {
    var charge = charges.get(chargeId);
    charge.put("amount_refunded", charge.path("amount_refunded").asLong() + cents);
    charge.put(
        "refunded", charge.path("amount_refunded").asLong() == charge.path("amount").asLong());
  }

  void dispute(String chargeId) {
    charges.get(chargeId).put("disputed", true);
  }

  private static ObjectNode charge(String id, long amount, String invoice, String intent) {
    var charge = JSON.createObjectNode();
    charge.put("id", id);
    charge.put("amount", amount);
    charge.put("amount_refunded", 0);
    charge.put("refunded", false);
    charge.put("disputed", false);
    charge.put("invoice", invoice);
    charge.put("payment_intent", intent);
    return charge;
  }

  /** Completes a setup session as a customer saving their card does. */
  void saveCard(String sessionId, String paymentMethodId, String brand, String last4) {
    var session = sessions.get(sessionId);
    session.put("status", "complete");
    var method = session.putObject("setup_intent").putObject("payment_method");
    method.put("id", paymentMethodId);
    method.putObject("card").put("brand", brand).put("last4", last4);
  }

  /** Forgets what earlier tests sent, so each test sees only its own requests. */
  void reset() {
    status = 0;
    declineCards = false;
    checkouts.clear();
    invoices.clear();
    charges.clear();
    detached.clear();
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
      if ("setup".equals(form.get("mode"))) {
        session.put("mode", "setup");
        session.put("status", "open");
        session.put("customer", form.get("customer"));
        session.put("url", "https://checkout.stripe.test/" + id);
        session.putNull("setup_intent");
        sessions.put(id, session);
        lastSession = id;
        answer(exchange, 200, JSON.writeValueAsString(session));
        return;
      }
      session.put("status", "open");
      session.put("payment_status", "unpaid");
      var amount = Long.parseLong(form.get("line_items[0][price_data][unit_amount]"));
      session.put("amount_subtotal", amount);
      var rate = taxRates.get(form.get("line_items[0][tax_rates][0]"));
      var tax =
          rate == null
              ? 0
              : rate.path("percentage")
                  .decimalValue()
                  .multiply(BigDecimal.valueOf(amount))
                  .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                  .longValueExact();
      session.put("amount_total", amount + tax);
      session.put("currency", form.get("line_items[0][price_data][currency]"));
      session.put("url", "https://checkout.stripe.test/" + id);
      session.putNull("invoice");
      sessions.put(id, session);
      lastSession = id;
      answer(exchange, 200, JSON.writeValueAsString(session));
      return;
    }
    if (exchange.getRequestURI().getPath().equals("/v1/checkout/sessions")) {
      var query = exchange.getRequestURI().getQuery();
      var intent = query.replaceAll(".*payment_intent=([^&]+).*", "$1");
      var list = JSON.createObjectNode();
      var data = list.putArray("data");
      sessions.values().stream()
          .filter(s -> intent.equals(s.path("payment_intent").asString()))
          .forEach(data::add);
      answer(exchange, 200, JSON.writeValueAsString(list));
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

  private void taxRates(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    if (exchange.getRequestMethod().equals("POST")) {
      var form = form(exchange);
      var rate = JSON.createObjectNode();
      rate.put("id", "txr_" + ids.incrementAndGet());
      rate.put("display_name", form.get("display_name"));
      rate.put("percentage", Double.parseDouble(form.get("percentage")));
      rate.put("inclusive", Boolean.parseBoolean(form.get("inclusive")));
      taxRates.put(rate.path("id").asString(), rate);
      answer(exchange, 200, JSON.writeValueAsString(rate));
      return;
    }
    var list = JSON.createObjectNode();
    list.putArray("data").addAll(taxRates.values());
    answer(exchange, 200, JSON.writeValueAsString(list));
  }

  private void invoices(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    if (exchange.getRequestMethod().equals("POST")) {
      keyed(exchange, this::invoiceAction);
      return;
    }
    var id = exchange.getRequestURI().getPath().substring("/v1/invoices/".length());
    if (invoices.containsKey(id)) {
      var invoice = invoices.get(id).deepCopy();
      invoice.put("invoice_pdf", "https://pay.stripe.test/" + id + "/pdf");
      answer(exchange, 200, JSON.writeValueAsString(invoice));
      return;
    }
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

  // POST /v1/invoices creates one; /v1/invoices/{id}/finalize, /pay and /void move it on.
  private Answer invoiceAction(String path, Map<String, String> form) {
    var parts = path.split("/");
    if (parts.length == 3) {
      var invoice = JSON.createObjectNode();
      var id = "in_" + ids.incrementAndGet();
      invoice.put("id", id);
      invoice.put("status", "draft");
      invoice.put("customer", form.get("customer"));
      invoice.put("currency", form.get("currency"));
      invoice.put("default_payment_method", form.get("default_payment_method"));
      invoice.put("metadata_top_up_id", form.get("metadata[top_up_id]"));
      invoice.put("subtotal", 0);
      invoice.put("total", 0);
      invoice.putNull("number");
      invoices.put(id, invoice);
      return new Answer(200, JSON.writeValueAsString(invoice));
    }
    var invoice = invoices.get(parts[3]);
    if (invoice == null) {
      return new Answer(404, "{\"error\":{\"message\":\"No such invoice\"}}");
    }
    switch (parts[4]) {
      case "finalize" -> {
        invoice.put("status", "open");
        invoice.put("number", "WSAI-" + parts[3]);
      }
      case "pay" -> {
        if (declineCards) {
          return new Answer(402, "{\"error\":{\"message\":\"Your card was declined.\"}}");
        }
        invoice.put("status", "paid");
        charges.put(
            "ch_" + parts[3],
            charge("ch_" + parts[3], invoice.path("total").asLong(), parts[3], "pi_" + parts[3]));
      }
      case "void" -> invoice.put("status", "void");
      default -> {
        return new Answer(404, "{}");
      }
    }
    return new Answer(200, JSON.writeValueAsString(invoice));
  }

  private void charges(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    var charge = charges.get(exchange.getRequestURI().getPath().substring("/v1/charges/".length()));
    if (charge == null) {
      answer(exchange, 404, "{\"error\":{\"message\":\"No such charge\"}}");
      return;
    }
    answer(exchange, 200, JSON.writeValueAsString(charge));
  }

  private void invoiceItems(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    keyed(
        exchange,
        (path, form) -> {
          var invoice = invoices.get(form.get("invoice"));
          var amount = Long.parseLong(form.get("amount"));
          var rate = taxRates.get(form.get("tax_rates[0]"));
          var tax =
              rate == null
                  ? 0
                  : rate.path("percentage")
                      .decimalValue()
                      .multiply(BigDecimal.valueOf(amount))
                      .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                      .longValueExact();
          invoice.put("subtotal", invoice.path("subtotal").asLong() + amount);
          invoice.put("total", invoice.path("total").asLong() + amount + tax);
          return new Answer(200, "{\"id\":\"ii_" + ids.incrementAndGet() + "\"}");
        });
  }

  private void paymentMethods(HttpExchange exchange) throws IOException {
    if (refused(exchange)) {
      return;
    }
    var path = exchange.getRequestURI().getPath();
    detached.add(path.split("/")[3]);
    answer(exchange, 200, "{}");
  }

  private record Answer(int status, String body) {}

  // Answers a keyed request it has seen before as it did the first time, as Stripe does.
  private void keyed(
      HttpExchange exchange,
      java.util.function.BiFunction<String, Map<String, String>, Answer> handle)
      throws IOException {
    var key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
    var form = form(exchange);
    if (key != null && answered.containsKey(key)) {
      var cached = answered.get(key);
      var split = cached.indexOf(' ');
      answer(exchange, Integer.parseInt(cached.substring(0, split)), cached.substring(split + 1));
      return;
    }
    var result = handle.apply(exchange.getRequestURI().getPath(), form);
    if (key != null) {
      answered.put(key, result.status() + " " + result.body());
    }
    answer(exchange, result.status(), result.body());
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
