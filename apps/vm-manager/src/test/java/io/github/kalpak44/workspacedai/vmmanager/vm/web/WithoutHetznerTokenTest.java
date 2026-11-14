package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/** With no token the app still starts, and only what needs Hetzner answers 503. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WithoutHetznerTokenTest {

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void startsAndAnswersHealthy() throws Exception {
    assertThat(send("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
  }

  @Test
  void listsNoOptions() throws Exception {
    var response = send("GET", "/vm-options", null);
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("no Hetzner API token");
  }

  // The inventory cannot load without Hetzner, and nothing is created before it has.
  @Test
  void createsNothing() throws Exception {
    var response =
        send(
            "POST",
            "/vms",
            "{\"workspaceId\":\""
                + UUID.randomUUID()
                + "\",\"userId\":\""
                + UUID.randomUUID()
                + "\"}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(send("GET", "/vms", null).body()).isEqualTo("[]");
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Content-Type", "application/json")
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
