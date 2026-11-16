package io.github.kalpak44.workspacedai.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A local server answers as a DNS provider's Domain Connect API does. */
class DomainConnectTest {

  final Map<String, String> answers = new ConcurrentHashMap<>();
  final List<String> asked = new CopyOnWriteArrayList<>();
  final List<URI> guarded = new CopyOnWriteArrayList<>();
  HttpServer server;
  String host;
  List<String> txt;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          asked.add(path);
          var body = answers.get(path);
          var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(
              body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    host = "localhost:" + server.getAddress().getPort();
    txt = List.of(host + "/dc");
    answers.put(
        "/dc/v2/example.com/settings",
        "{\"providerId\":\"godaddy.com\",\"providerName\":\"GoDaddy\","
            + "\"providerDisplayName\":\"GoDaddy Inc\",\"urlSyncUX\":\"https://dcc.example.net\","
            + "\"urlAPI\":\"http://"
            + host
            + "/dc\"}");
    answers.put("/dc/v2/domainTemplates/providers/workspaced.online/services/workspace", "{}");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  DnsLookup dns() {
    return new DnsLookup() {
      @Override
      public Optional<String> zoneOf(String name) {
        return Optional.empty();
      }

      @Override
      public List<String> txt(String name) {
        if (!name.equals("_domainconnect.example.com")) {
          return List.of();
        }
        if (txt == null) {
          throw new DnsLookupException("timeout", null);
        }
        return txt;
      }

      @Override
      public List<String> ipv4(String name) {
        return List.of();
      }
    };
  }

  static DomainProperties properties() {
    return new DomainProperties(
        null,
        "_dck1",
        "workspaced.online",
        "https://app",
        "dns://unused",
        Duration.ofSeconds(2),
        20);
  }

  DomainConnect connect() {
    return new DomainConnect(
        dns(), properties(), JsonMapper.builder().build(), guarded::add, "http");
  }

  @Test
  void findsAProviderThatHasTheTemplate() {
    var provider = connect().discover("example.com", "workspace");

    assertThat(provider).contains(new DnsProvider("GoDaddy Inc", "https://dcc.example.net"));
    assertThat(guarded)
        .extracting(URI::getPath)
        .containsExactly(
            "/dc/v2/example.com/settings",
            "/dc/v2/domainTemplates/providers/workspaced.online/services/workspace");
  }

  @Test
  void findsNoneForATemplateTheProviderLacks() {
    assertThat(connect().discover("example.com", "workspace-apex")).isEmpty();
  }

  @Test
  void findsNoneWithoutADomainConnectRecord() {
    assertThat(connect().discover("other.com", "workspace")).isEmpty();
    assertThat(asked).isEmpty();
  }

  @Test
  void findsNoneWhenTheSettingsAreMissingOrUnusable() {
    answers.remove("/dc/v2/example.com/settings");
    assertThat(connect().discover("example.com", "workspace")).isEmpty();

    answers.put("/dc/v2/example.com/settings", "{\"urlSyncUX\":\"http://plain\",\"urlAPI\":\"x\"}");
    assertThat(connect().discover("example.com", "workspace")).isEmpty();

    answers.put("/dc/v2/example.com/settings", "not json");
    assertThat(connect().discover("example.com", "workspace")).isEmpty();
  }

  @Test
  void findsNoneWhenDnsOrTheGuardRefuses() {
    txt = null;
    assertThat(connect().discover("example.com", "workspace")).isEmpty();

    txt = List.of(host + "/dc");
    var guarded =
        new DomainConnect(
            dns(), properties(), JsonMapper.builder().build(), PublicAddresses::require, "http");
    assertThat(guarded.discover("example.com", "workspace")).isEmpty();
    assertThat(asked).isEmpty();
  }

  @Test
  void usesTheProviderNameWhenItGivesNoDisplayName() {
    answers.put(
        "/dc/v2/example.com/settings",
        "{\"providerName\":\"IONOS\",\"urlSyncUX\":\"https://sync\",\"urlAPI\":\"http://"
            + host
            + "/dc\"}");

    assertThat(connect().discover("example.com", "workspace"))
        .contains(new DnsProvider("IONOS", "https://sync"));
  }
}
