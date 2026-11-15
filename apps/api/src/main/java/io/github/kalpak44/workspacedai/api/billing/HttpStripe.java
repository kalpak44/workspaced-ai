package io.github.kalpak44.workspacedai.api.billing;

import java.io.IOException;
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

  private final BillingProperties properties;
  private final JsonMapper json;
  private final HttpClient http;

  public HttpStripe(BillingProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.stripe().timeout()).build();
  }

  @Override
  public String createCustomer(UUID userId, String name) {
    var form = new LinkedHashMap<String, String>();
    form.put("name", name);
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
