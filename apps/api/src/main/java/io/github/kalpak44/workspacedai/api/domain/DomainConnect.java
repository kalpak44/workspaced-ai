package io.github.kalpak44.workspacedai.api.domain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds out whether a zone's DNS provider applies one of our templates itself: its _domainconnect
 * record names the provider's API, whose settings name where the user approves a template, and the
 * provider answers 2xx for a template it has. That answer comes while a template is still limited
 * to our test account too, so only a provider we list as live counts. Anything else, a failure
 * included, means the user adds the records by hand, so this never fails an attach.
 */
@Component
public class DomainConnect {

  private static final Logger log = LoggerFactory.getLogger(DomainConnect.class);
  // Settings are a handful of fields; more than this is not an answer worth reading.
  private static final int MAX_BODY = 64 * 1024;

  private final DnsLookup dns;
  private final DomainProperties properties;
  private final JsonMapper json;
  private final Consumer<URI> guard;
  private final String scheme;
  private final HttpClient http;

  @Autowired
  DomainConnect(DnsLookup dns, DomainProperties properties, JsonMapper json) {
    this(dns, properties, json, PublicAddresses::require, "https");
  }

  /**
   * The guard decides which URLs may be called and the scheme how a provider's API is reached, so a
   * test can let a local server through.
   */
  DomainConnect(
      DnsLookup dns,
      DomainProperties properties,
      JsonMapper json,
      Consumer<URI> guard,
      String scheme) {
    this.dns = dns;
    this.properties = properties;
    this.json = json;
    this.guard = guard;
    this.scheme = scheme;
    // A redirect could lead anywhere the guard was never asked about.
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Optional<DnsProvider> discover(String zone, String serviceId) {
    try {
      var records = dns.txt("_domainconnect." + zone);
      if (records.isEmpty()) {
        return Optional.empty();
      }
      var settings =
          json.readTree(
              get(URI.create(
                      scheme + "://" + records.getFirst().strip() + "/v2/" + zone + "/settings"))
                  .orElse("{}"));
      var providerId = settings.path("providerId").asString("");
      if (!properties.liveProviders().contains(providerId)
          && !properties.testZones().contains(zone)) {
        log.info("{} is at {}, which has not taken our templates live", zone, providerId);
        return Optional.empty();
      }
      var syncUrl = settings.path("urlSyncUX").asString("");
      var apiUrl = settings.path("urlAPI").asString("");
      if (!syncUrl.startsWith("https://") || apiUrl.isBlank()) {
        return Optional.empty();
      }
      var template =
          URI.create(
              apiUrl.replaceAll("/+$", "")
                  + "/v2/domainTemplates/providers/"
                  + properties.providerId()
                  + "/services/"
                  + serviceId);
      if (get(template).isEmpty()) {
        return Optional.empty();
      }
      var name = settings.path("providerDisplayName").asString("");
      return Optional.of(
          new DnsProvider(
              name.isBlank() ? settings.path("providerName").asString("") : name, syncUrl));
    } catch (DnsLookupException | IllegalArgumentException | IOException | JacksonException e) {
      log.info("{} gets no Domain Connect: {}", zone, e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  // The body of a 2xx answer, or empty for any other.
  private Optional<String> get(URI uri) throws IOException, InterruptedException {
    guard.accept(uri);
    var request =
        HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .timeout(properties.timeout())
            .GET()
            .build();
    var response = http.send(request, BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() / 100 != 2) {
        return Optional.empty();
      }
      return Optional.of(new String(body.readNBytes(MAX_BODY), StandardCharsets.UTF_8));
    }
  }
}
