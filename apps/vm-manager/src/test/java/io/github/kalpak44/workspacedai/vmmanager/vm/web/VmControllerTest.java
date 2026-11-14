package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetzner;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDto;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** End to end on a random port, with Hetzner answered by a local fake. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class VmControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  private static final FakeHetzner HETZNER = new FakeHetzner();
  private static final UUID USER = UUID.randomUUID();

  @DynamicPropertySource
  static void hetzner(DynamicPropertyRegistry registry) {
    registry.add("workspaced.hetzner.token", () -> "t0ken");
    registry.add("workspaced.hetzner.base-url", HETZNER::url);
  }

  @AfterAll
  static void stop() {
    HETZNER.close();
  }

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void createsOneVmPerOwner() throws Exception {
    var owner = UUID.randomUUID().toString();
    var first = send("POST", "", "{\"workspaceId\":\"" + owner + "\",\"userId\":\"" + USER + "\"}");
    assertThat(first.statusCode()).isEqualTo(201);
    var vm = JSON.readValue(first.body(), VmDto.class);
    assertThat(vm.status()).isEqualTo("PROVISIONING");
    assertThat(vm.serverType()).isEqualTo("cx23");
    assertThat(first.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/vms/" + vm.id()));
    var server = HETZNER.servers.get(vm.serverId());
    assertThat(server.path("labels").path("workspace-id").asString()).isEqualTo(owner);
    assertThat(server.path("labels").path("user-id").asString()).isEqualTo(USER.toString());
    assertThat(vm.ipv4()).isEqualTo(server.path("public_net").path("ipv4").path("ip").asString());

    var again =
        send(
            "POST",
            "",
            "{\"workspaceId\":\""
                + owner
                + "\",\"userId\":\""
                + USER
                + "\",\"serverType\":\"cx33\"}");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(again.body(), VmDto.class).id()).isEqualTo(vm.id());
    assertThat(HETZNER.servers.values())
        .filteredOn(s -> s.path("labels").path("workspace-id").asString().equals(owner))
        .hasSize(1);

    var listed = send("GET", "?workspaceId=" + owner, null);
    assertThat(JSON.readValue(listed.body(), new TypeReference<List<VmDto>>() {}))
        .extracting(VmDto::id)
        .containsExactly(vm.id());
  }

  @Test
  void followsReadyRestartAndHeartbeat() throws Exception {
    var vm = create();
    assertThat(vm.lastSeenAt()).isNull();

    var ready = JSON.readValue(send("POST", "/" + vm.id() + "/ready", null).body(), VmDto.class);
    assertThat(ready.status()).isEqualTo("READY");
    assertThat(ready.lastSeenAt()).isNotNull();

    var restart = send("POST", "/" + vm.id() + "/restart", null);
    assertThat(restart.statusCode()).isEqualTo(202);
    assertThat(JSON.readValue(restart.body(), VmDto.class).status()).isEqualTo("RESTARTING");

    assertThat(send("POST", "/" + vm.id() + "/heartbeat", null).statusCode()).isEqualTo(204);
    var fetched = JSON.readValue(send("GET", "/" + vm.id(), null).body(), VmDto.class);
    assertThat(fetched.status()).isEqualTo("RESTARTING");
    assertThat(fetched.lastSeenAt()).isAfterOrEqualTo(ready.lastSeenAt());
  }

  @Test
  void deletesAndThenNoLongerFindsIt() throws Exception {
    var vm = create();
    assertThat(send("DELETE", "/" + vm.id(), null).statusCode()).isEqualTo(204);
    assertThat(HETZNER.servers).doesNotContainKey(vm.serverId());
    assertThat(send("GET", "/" + vm.id(), null).statusCode()).isEqualTo(404);
    assertThat(send("DELETE", "/" + vm.id(), null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/" + vm.id() + "/ready", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/" + vm.id() + "/restart", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/" + vm.id() + "/heartbeat", null).statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsAMissingOrMalformedWorkspaceId() throws Exception {
    var valid = "\"userId\":\"" + USER + "\"";
    for (var body :
        new String[] {
          "{" + valid + "}",
          "{\"workspaceId\":\"not-a-uuid\"," + valid + "}",
          "{\"workspaceId\":\"" + UUID.randomUUID() + "\"}"
        }) {
      var response = send("POST", "", body);
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    }
  }

  @Test
  void rejectsAServerTypeNotOffered() throws Exception {
    var response =
        send(
            "POST",
            "",
            "{\"workspaceId\":\""
                + UUID.randomUUID()
                + "\",\"userId\":\""
                + USER
                + "\",\"serverType\":\"cx53\"}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("cx23, cx33");
  }

  @Test
  void createsTheSizeAsked() throws Exception {
    var response =
        send(
            "POST",
            "",
            "{\"workspaceId\":\""
                + UUID.randomUUID()
                + "\",\"userId\":\""
                + USER
                + "\",\"serverType\":\"cx33\"}");
    assertThat(JSON.readValue(response.body(), VmDto.class).serverType()).isEqualTo("cx33");
  }

  @Test
  void streamsExistingVmsOnConnect() throws Exception {
    var vm = create();
    var request =
        HttpRequest.newBuilder(URI.create(base() + "/events"))
            .header("Accept", "text/event-stream")
            .build();
    try (var lines = http.send(request, BodyHandlers.ofLines()).body()) {
      var first =
          lines
              .filter(line -> line.startsWith("data:") && line.contains(vm.id().toString()))
              .findFirst();
      assertThat(first).isPresent();
    }
  }

  @Test
  void streamsChangesMadeAfterConnecting() throws Exception {
    // One VM exists first, so the stream has an event to send and its headers arrive.
    create();
    var request =
        HttpRequest.newBuilder(URI.create(base() + "/events"))
            .header("Accept", "text/event-stream")
            .build();
    try (var lines = http.send(request, BodyHandlers.ofLines()).body()) {
      var later = create();
      var seen =
          lines
              .filter(line -> line.startsWith("data:") && line.contains(later.id().toString()))
              .findFirst();
      assertThat(seen).isPresent();
    }
  }

  private VmDto create() throws Exception {
    var response =
        send(
            "POST",
            "",
            "{\"workspaceId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + USER + "\"}");
    return JSON.readValue(response.body(), VmDto.class);
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/json")
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  private String base() {
    return "http://localhost:" + port + "/api/v1/vms";
  }
}
