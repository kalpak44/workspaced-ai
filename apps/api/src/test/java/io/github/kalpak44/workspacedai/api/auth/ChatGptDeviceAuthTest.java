package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class ChatGptDeviceAuthTest {
  HttpServer server;
  ChatGptDeviceAuth auth;
  int pollStatus = 404;
  final List<String> bodies = new ArrayList<>();
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String body;
          int status = 200;
          if (path.endsWith("/usercode"))
            body = "{\"device_auth_id\":\"device\",\"user_code\":\"ABCD\",\"interval\":\"5\"}";
          else if (path.endsWith("/deviceauth/token")) {
            status = pollStatus;
            body =
                status == 200
                    ? "{\"authorization_code\":\"code+value\",\"code_verifier\":\"verifier\"}"
                    : "{\"error\":\"private-provider-body\"}";
          } else {
            var claims =
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        "{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"account\"}}"
                            .getBytes(StandardCharsets.UTF_8));
            body =
                json.writeValueAsString(
                    Map.of(
                        "access_token",
                        "header." + claims + ".signature",
                        "refresh_token",
                        "private-refresh",
                        "expires_in",
                        3600));
          }
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    auth =
        new ChatGptDeviceAuth(
            json, URI.create("http://localhost:" + server.getAddress().getPort()));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void exchangesDeviceApprovalForNativeOpenCodeCredentials() {
    assertThat(auth.start().path("user_code").asString()).isEqualTo("ABCD");
    assertThat(auth.poll("{\"device_auth_id\":\"device\",\"user_code\":\"ABCD\"}")).isNull();
    pollStatus = 200;
    var credential =
        json.readTree(auth.poll("{\"device_auth_id\":\"device\",\"user_code\":\"ABCD\"}"));
    assertThat(credential.path("methodID").asString()).isEqualTo("chatgpt-headless");
    assertThat(credential.path("metadata").path("accountID").asString()).isEqualTo("account");
    assertThat(credential.path("refresh").asString()).isEqualTo("private-refresh");
    assertThat(bodies.getLast())
        .contains(
            "grant_type=authorization_code",
            "code=code%2Bvalue",
            "code_verifier=verifier",
            "redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback");
  }

  @Test
  void suppressesProviderErrorBodies() {
    pollStatus = 500;
    assertThatThrownBy(() -> auth.poll("{}"))
        .hasMessageContaining("sign-in is unavailable")
        .hasMessageNotContaining("private-provider-body");
  }
}
