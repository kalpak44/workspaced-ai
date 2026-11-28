package io.github.kalpak44.workspacedai.api.billing;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls Stripe's REST API, which takes form-encoded bodies and answers JSON. */
@Component
public class HttpStripe implements Stripe {

  // Above Stripe's 30-minute floor; an abandoned checkout stops showing as pending within it.
  private static final Duration CHECKOUT_LIFETIME = Duration.ofMinutes(60);

  private static final String VAT = "VAT";

  private final BillingProperties properties;
  private final JsonMapper json;
  private final HttpClient http;
  private volatile String vatRateId;

  public HttpStripe(BillingProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.stripe().timeout()).build();
  }

  @Override
  public String createCustomer(UUID userId) {
    var form = new LinkedHashMap<String, String>();
    form.put("metadata[user_id]", userId.toString());
    return read(send(post("/v1/customers", form, null))).path("id").asString();
  }

  @Override
  public CheckoutSession createCheckout(
      String customerId, UUID topUpId, long amountCents, URI successUrl, URI cancelUrl) {
    var form = new LinkedHashMap<String, String>();
    form.put("mode", "payment");
    form.put("customer", customerId);
    form.put("client_reference_id", topUpId.toString());
    form.put("metadata[top_up_id]", topUpId.toString());
    form.put("line_items[0][quantity]", "1");
    form.put("line_items[0][price_data][currency]", properties.currency());
    form.put("line_items[0][price_data][unit_amount]", Long.toString(amountCents));
    form.put("line_items[0][price_data][product_data][name]", "Balance top-up");
    // The amount is what reaches the balance; VAT is charged on top and shown as tax on the
    // invoice.
    form.put("line_items[0][tax_rates][0]", vatRate());
    form.put("invoice_creation[enabled]", "true");
    // The invoice is addressed to whoever pays, so their address is asked for and kept.
    form.put("billing_address_collection", "required");
    form.put("customer_update[address]", "auto");
    form.put("customer_update[name]", "auto");
    form.put("success_url", successUrl.toString());
    form.put("cancel_url", cancelUrl.toString());
    form.put("expires_at", Long.toString(Instant.now().plus(CHECKOUT_LIFETIME).getEpochSecond()));
    return session(read(send(post("/v1/checkout/sessions", form, topUpId.toString()))));
  }

  /**
   * The account's active, exclusive tax rate named VAT at the configured percentage, created on
   * first use, so the rate is set in one place rather than in Stripe's dashboard as well.
   */
  private String vatRate() {
    var cached = vatRateId;
    if (cached != null) {
      return cached;
    }
    var percent = BigDecimal.valueOf(properties.vatPercent());
    var rates = read(send(get("/v1/tax_rates?active=true&inclusive=false&limit=100"))).path("data");
    for (var rate : rates) {
      if (VAT.equals(rate.path("display_name").asString())
          && rate.path("percentage").decimalValue().compareTo(percent) == 0) {
        return vatRateId = rate.path("id").asString();
      }
    }
    var form = new LinkedHashMap<String, String>();
    form.put("display_name", VAT);
    form.put("percentage", percent.toPlainString());
    form.put("inclusive", "false");
    // Two top-ups racing to create the rate get the same one back.
    var key = "vat-rate-" + percent.toPlainString();
    return vatRateId = read(send(post("/v1/tax_rates", form, key))).path("id").asString();
  }

  @Override
  public CheckoutSession createCardSetup(String customerId, String successUrl, URI cancelUrl) {
    var form = new LinkedHashMap<String, String>();
    form.put("mode", "setup");
    form.put("customer", customerId);
    form.put("currency", properties.currency());
    form.put("payment_method_types[0]", "card");
    form.put("metadata[purpose]", "auto-top-up");
    form.put("success_url", successUrl);
    form.put("cancel_url", cancelUrl.toString());
    form.put("expires_at", Long.toString(Instant.now().plus(CHECKOUT_LIFETIME).getEpochSecond()));
    return session(read(send(post("/v1/checkout/sessions", form, null))));
  }

  @Override
  public Optional<SavedCard> savedCard(String sessionId) {
    var session =
        read(
            send(
                get(
                    "/v1/checkout/sessions/"
                        + encode(sessionId)
                        + "?expand[]=setup_intent.payment_method")));
    var method = session.path("setup_intent").path("payment_method");
    if (!"complete".equals(session.path("status").asString()) || !method.isObject()) {
      return Optional.empty();
    }
    var card = method.path("card");
    var brand = card.path("brand").asString("card");
    var label =
        brand.substring(0, 1).toUpperCase(Locale.ROOT)
            + brand.substring(1)
            + " •••• "
            + card.path("last4").asString("");
    return Optional.of(
        new SavedCard(session.path("customer").asString(), method.path("id").asString(), label));
  }

  /**
   * Creates the invoice with only this line, so nothing else pending on the customer is charged
   * with it, then finalizes it and pays it off session. Each step has a key of its own, so a retry
   * after a dropped answer picks up where the last attempt got to.
   */
  @Override
  public ChargedInvoice chargeCard(
      String customerId, String paymentMethodId, UUID topUpId, long amountCents) {
    var key = "top-up-" + topUpId;
    var invoiceForm = new LinkedHashMap<String, String>();
    invoiceForm.put("customer", customerId);
    invoiceForm.put("collection_method", "charge_automatically");
    invoiceForm.put("auto_advance", "false");
    invoiceForm.put("currency", properties.currency());
    invoiceForm.put("default_payment_method", paymentMethodId);
    invoiceForm.put("pending_invoice_items_behavior", "exclude");
    invoiceForm.put("description", "Automatic balance top-up");
    invoiceForm.put("metadata[top_up_id]", topUpId.toString());
    var invoice = read(send(post("/v1/invoices", invoiceForm, key + "-invoice"))).path("id");
    var id = encode(invoice.asString());
    var item = new LinkedHashMap<String, String>();
    item.put("customer", customerId);
    item.put("invoice", invoice.asString());
    item.put("currency", properties.currency());
    item.put("amount", Long.toString(amountCents));
    item.put("description", "Balance top-up");
    item.put("tax_rates[0]", vatRate());
    read(send(post("/v1/invoiceitems", item, key + "-item")));
    read(send(post("/v1/invoices/" + id + "/finalize", Map.of(), key + "-finalize")));
    var pay = new LinkedHashMap<String, String>();
    pay.put("off_session", "true");
    pay.put("payment_method", paymentMethodId);
    try {
      return invoice(read(send(post("/v1/invoices/" + id + "/pay", pay, key + "-pay"))));
    } catch (StripeFailedException e) {
      try {
        send(post("/v1/invoices/" + id + "/void", Map.of(), key + "-void"));
      } catch (RuntimeException voidFailed) {
        // An open invoice nobody pays is untidy, not wrong: auto_advance keeps Stripe from it.
        e.addSuppressed(voidFailed);
      }
      throw new CardDeclinedException(stripeReason(e));
    }
  }

  @Override
  public Optional<AutomaticInvoice> automaticInvoice(String invoiceId) {
    var response = send(get("/v1/invoices/" + encode(invoiceId)));
    return response.statusCode() == 404 ? Optional.empty() : automatic(read(response));
  }

  @Override
  public Optional<AutomaticInvoice> findAutomaticInvoice(String customerId, UUID topUpId) {
    String cursor = null;
    do {
      var page =
          read(
              send(
                  get(
                      "/v1/invoices?limit=100&customer="
                          + encode(customerId)
                          + (cursor == null ? "" : "&starting_after=" + encode(cursor)))));
      var data = page.path("data");
      for (var row : data) {
        var found =
            automatic(row)
                .filter(i -> i.topUpId().equals(topUpId) && i.customerId().equals(customerId));
        if (found.isPresent()) return found;
      }
      if (!page.path("has_more").asBoolean()) return Optional.empty();
      var next = data.isEmpty() ? null : text(data.get(data.size() - 1).path("id"));
      if (next == null || next.equals(cursor))
        throw new StripeFailedException("invalid invoice pagination");
      cursor = next;
    } while (true);
  }

  private static Optional<AutomaticInvoice> automatic(JsonNode node) {
    var customer = text(node.path("customer"));
    var topUp = text(node.path("metadata").path("top_up_id"));
    if (customer == null || topUp == null) return Optional.empty();
    try {
      return Optional.of(new AutomaticInvoice(UUID.fromString(topUp), customer, invoice(node)));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  @Override
  public void detachCard(String paymentMethodId) {
    send(
        post(
            "/v1/payment_methods/" + encode(paymentMethodId) + "/detach",
            Map.of(),
            "detach-" + paymentMethodId));
  }

  // Stripe's message is the last part of the failure's reason.
  private static String stripeReason(StripeFailedException e) {
    var reason = e.getReason() == null ? "" : e.getReason();
    var colon = reason.lastIndexOf(": ");
    return colon < 0 ? reason : reason.substring(colon + 2);
  }

  private static ChargedInvoice invoice(JsonNode invoice) {
    return new ChargedInvoice(
        invoice.path("id").asString(),
        text(invoice.path("number")),
        invoice.path("status").asString(),
        invoice.path("subtotal").asLong(),
        invoice.path("total").asLong(),
        invoice.path("currency").asString());
  }

  @Override
  public Optional<Charge> charge(String chargeId) {
    var response = send(get("/v1/charges/" + encode(chargeId)));
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    var charge = read(response);
    var invoice = text(charge.path("invoice"));
    var intent = text(charge.path("payment_intent"));
    String session = null;
    // A Checkout payment's invoice is issued after it, and its charge may not name it.
    if (invoice == null && intent != null) {
      var sessions =
          read(send(get("/v1/checkout/sessions?limit=1&payment_intent=" + encode(intent))))
              .path("data");
      session = sessions.isEmpty() ? null : text(sessions.get(0).path("id"));
    }
    return Optional.of(
        new Charge(
            charge.path("id").asString(),
            charge.path("amount").asLong(),
            charge.path("amount_refunded").asLong(),
            charge.path("disputed").asBoolean(),
            invoice,
            session));
  }

  @Override
  public CheckoutSession checkout(String sessionId) {
    return session(
        read(send(get("/v1/checkout/sessions/" + encode(sessionId) + "?expand[]=invoice"))));
  }

  @Override
  public Optional<URI> invoicePdf(String invoiceId) {
    var response = send(get("/v1/invoices/" + encode(invoiceId)));
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    var pdf = read(response).path("invoice_pdf");
    return pdf.isString() ? Optional.of(URI.create(pdf.asString())) : Optional.empty();
  }

  private static CheckoutSession session(JsonNode session) {
    var invoice = session.path("invoice");
    var url = session.path("url");
    return new CheckoutSession(
        session.path("id").asString(),
        session.path("status").asString(),
        session.path("payment_status").asString(),
        session.path("amount_subtotal").asLong(),
        session.path("amount_total").asLong(),
        session.path("currency").asString(),
        url.isString() ? URI.create(url.asString()) : null,
        invoice.isObject() ? invoice.path("id").asString() : text(invoice),
        invoice.isObject() ? text(invoice.path("number")) : null);
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.asString() : null;
  }

  private HttpRequest.Builder get(String path) {
    return request(path).GET();
  }

  private HttpRequest.Builder post(String path, Map<String, String> form, String idempotencyKey) {
    var body =
        form.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    var request =
        request(path)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
  }

  private HttpRequest.Builder request(String path) {
    var stripe = properties.stripe();
    if (!stripe.isConfigured()) {
      throw new PaymentsUnavailableException("no Stripe account is configured", null);
    }
    var base = stripe.url().toString().replaceAll("/+$", "");
    return HttpRequest.newBuilder(URI.create(base + path))
        .header("Authorization", "Bearer " + stripe.secretKey())
        .header("Accept", "application/json")
        .timeout(stripe.timeout());
  }

  private HttpResponse<String> send(HttpRequest.Builder request) {
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new PaymentsUnavailableException("Stripe cannot be reached", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PaymentsUnavailableException("interrupted while calling Stripe", e);
    }
    var status = response.statusCode();
    if (status == 429 || status >= 500) {
      throw new PaymentsUnavailableException("Stripe answered " + status, null);
    }
    if (status != 200 && status != 404) {
      throw new StripeFailedException(
          "answered "
              + status
              + " to "
              + response.request().method()
              + " "
              + response.request().uri().getPath()
              + ": "
              + message(response));
    }
    return response;
  }

  private JsonNode read(HttpResponse<String> response) {
    if (response.statusCode() != 200) {
      throw new StripeFailedException(
          "has no " + response.request().uri().getPath() + ": " + message(response));
    }
    try {
      return json.readTree(response.body());
    } catch (JacksonException e) {
      throw new StripeFailedException("sent a body that is not JSON");
    }
  }

  /** Stripe's own explanation, from its error object. */
  private String message(HttpResponse<String> response) {
    try {
      var message = json.readTree(response.body()).path("error").path("message");
      return message.isString() ? message.asString() : "status " + response.statusCode();
    } catch (JacksonException e) {
      return "status " + response.statusCode();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
