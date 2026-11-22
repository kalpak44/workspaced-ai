package io.github.kalpak44.workspacedai.api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpOpenRouterManagementTest {
  HttpServer server;
  HttpOpenRouterManagement management;
  String auth;
  JsonNode body;
  String method;
  int status = 201;
  String response =
      "{\"key\":\"issued-secret\",\"data\":{\"hash\":\"key-hash\",\"limit\":1,\"limit_reset\":null}}";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/keys",
        exchange -> {
          auth = exchange.getRequestHeaders().getFirst("Authorization");
          method = exchange.getRequestMethod();
          if (method.equals("POST"))
            body = JsonMapper.builder().build().readTree(exchange.getRequestBody());
          var bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    management =
        new HttpOpenRouterManagement(
            "backend-management-secret",
            URI.create("http://localhost:" + server.getAddress().getPort() + "/keys"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void setsOneDollarLifetimeBudgetForEveryNewKey() {
    var owner = UUID.randomUUID();
    var key = management.createTrial(owner);
    assertThat(auth).isEqualTo("Bearer backend-management-secret");
    assertThat(body.path("limit").decimalValue()).isEqualByComparingTo("1");
    assertThat(body.path("limit_reset").isNull()).isTrue();
    assertThat(body.path("include_byok_in_limit").asBoolean()).isTrue();
    assertThat(body.path("name").asString()).isEqualTo("workspaced trial: " + owner);
    assertThat(key.value()).isEqualTo("issued-secret");
    assertThat(key.toString()).doesNotContain("issued-secret", "backend-management-secret");
  }

  @Test
  void refusesUnboundedOrResettingKeysAndDoesNotReturnProviderErrors() {
    response =
        "{\"key\":\"issued-secret\",\"data\":{\"hash\":\"key-hash\",\"limit\":null,\"limit_reset\":null}}";
    assertThatThrownBy(() -> management.createTrial(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageNotContaining("issued-secret");
    response =
        "{\"key\":\"issued-secret\",\"data\":{\"hash\":\"key-hash\",\"limit\":1,\"limit_reset\":\"daily\"}}";
    assertThatThrownBy(() -> management.createTrial(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class);
    status = 500;
    response = "backend-management-secret";
    assertThatThrownBy(() -> management.createTrial(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageNotContaining("backend-management-secret");
  }

  @Test
  void canCleanUpAKeyThatCouldNotBeSavedWithoutReturningItsValue() {
    status = 200;
    response = "{\"deleted\":true}";
    management.revoke("key-hash");
    assertThat(method).isEqualTo("DELETE");
    assertThat(auth).isEqualTo("Bearer backend-management-secret");
  }

  @Test
  void cannotIssueKeysWithoutBackendConfiguration() {
    var disabled = new HttpOpenRouterManagement("");
    assertThat(disabled.configured()).isFalse();
    assertThatThrownBy(() -> disabled.createTrial(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class);
  }
}
