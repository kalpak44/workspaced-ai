package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls Cloudflare's DNS records API over HTTP with a token limited to the one zone. */
@Component
public class HttpCloudflareDns implements CloudflareDns {

  private static final int PER_PAGE = 5000;

  private final CloudflareProperties properties;
  private final JsonMapper json;
  private final HttpClient http;

  public HttpCloudflareDns(CloudflareProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
  }

  @Override
  public boolean isConfigured() {
    return properties.isConfigured();
  }

  @Override
  public List<DnsRecord> records(String nameSuffix) {
    var records = new ArrayList<DnsRecord>();
    var suffix = URLEncoder.encode(nameSuffix, StandardCharsets.UTF_8);
    var page = 1;
    var pages = 1;
    while (page <= pages) {
      var body =
          send("GET", "?name.endswith=" + suffix + "&per_page=" + PER_PAGE + "&page=" + page, null);
      body.path("result")
          .forEach(
              r ->
                  records.add(
                      new DnsRecord(
                          r.path("id").asString(),
                          r.path("type").asString(),
                          r.path("name").asString(),
                          r.path("content").asString())));
      pages = body.path("result_info").path("total_pages").asInt(1);
      page++;
    }
    return List.copyOf(records);
  }

  @Override
  public void create(String type, String name, String content, int ttl) {
    var body =
        json.createObjectNode()
            .put("type", type)
            .put("name", name)
            .put("content", content)
            .put("ttl", ttl)
            .put("proxied", false);
    send("POST", "", body);
  }

  @Override
  public void update(String id, String content) {
    send("PATCH", "/" + id, json.createObjectNode().put("content", content));
  }

  @Override
  public void delete(String id) {
    try {
      send("DELETE", "/" + id, null);
    } catch (Gone _) {
      // Already deleted, by an earlier attempt or by hand.
    }
  }

  private JsonNode send(String method, String path, JsonNode body) {
    if (!properties.isConfigured()) {
      throw new CloudflareException.NotConfigured();
    }
    var base = properties.baseUrl().toString().replaceAll("/+$", "");
    var request =
        HttpRequest.newBuilder(
                URI.create(base + "/zones/" + properties.zoneId() + "/dns_records" + path))
            .header("Authorization", "Bearer " + properties.token())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(properties.timeout())
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    try {
      var response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        throw new Gone(method + " " + path);
      }
      var answer = json.readTree(response.body());
      if (response.statusCode() / 100 != 2 || !answer.path("success").asBoolean(false)) {
        throw new CloudflareException(
            "Cloudflare answered "
                + response.statusCode()
                + " on "
                + method
                + " dns_records"
                + path
                + ": "
                + errors(answer));
      }
      return answer;
    } catch (IOException | JacksonException e) {
      throw new CloudflareException("could not reach Cloudflare on " + method + " " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CloudflareException("interrupted on " + method + " " + path + " at Cloudflare", e);
    }
  }

  // Cloudflare names what it refused in errors[].message, which is what an operator needs.
  private static String errors(JsonNode answer) {
    var messages = new ArrayList<String>();
    answer.path("errors").forEach(e -> messages.add(e.path("message").asString()));
    return messages.isEmpty() ? "no reason given" : String.join("; ", messages);
  }

  /** A 404: only a delete treats it as success, everywhere else it is a failure like any other. */
  private static final class Gone extends CloudflareException {
    Gone(String request) {
      super("Cloudflare answered 404 on " + request);
    }
  }
}
