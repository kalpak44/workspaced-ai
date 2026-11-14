package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetzner;
import io.github.kalpak44.workspacedai.vmmanager.vault.FakeVault;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.HarnessDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDto;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
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
  private static final FakeVault VAULT = new FakeVault();
  private static final UUID USER = UUID.randomUUID();
  // Closes the userId string it follows, so a body reads USER + KEY + "}".
  private static final String KEY = "\",\"openRouterApiKey\":\"sk-or-v1-test\"";
  private static final String IMAGE = "ghcr.io/kalpak44/workspaced-ai-harness:85c2bd5";

  @DynamicPropertySource
  static void hetzner(DynamicPropertyRegistry registry) {
    registry.add("workspaced.hetzner.token", () -> "t0ken");
    registry.add("workspaced.hetzner.base-url", HETZNER::url);
    registry.add("workspaced.vault.url", VAULT::url);
    registry.add("workspaced.vault.token", () -> FakeVault.ROOT_TOKEN);
    registry.add("workspaced.harness.image", () -> IMAGE);
  }

  @AfterAll
  static void stop() {
    HETZNER.close();
    VAULT.close();
  }

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void createsOneVmPerOwner() throws Exception {
    var owner = UUID.randomUUID().toString();
    var first =
        send("POST", "", "{\"workspaceId\":\"" + owner + "\",\"userId\":\"" + USER + KEY + "}");
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
                + KEY
                + ",\"serverType\":\"cx33\"}");
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

    var heartbeat = send("POST", "/" + vm.id() + "/heartbeat", null);
    assertThat(heartbeat.statusCode()).isEqualTo(200);
    var beat = JSON.readValue(heartbeat.body(), VmDto.class);
    assertThat(beat.status()).isEqualTo("RESTARTING");
    assertThat(beat.lastSeenAt()).isAfterOrEqualTo(ready.lastSeenAt());
    assertThat(JSON.readValue(send("GET", "/" + vm.id(), null).body(), VmDto.class))
        .isEqualTo(beat);
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
    assertThat(send("GET", "/" + vm.id() + "/harness", null).statusCode()).isEqualTo(404);
  }

  @Test
  void handsTheHarnessItsImageAndEveryValueAndListsNoneOfTheSystemOnes() throws Exception {
    var response =
        send(
            "POST",
            "",
            "{\"workspaceId\":\""
                + UUID.randomUUID()
                + "\",\"userId\":\""
                + USER
                + KEY
                + ",\"environment\":{\"OPENROUTER_BASE_URL\":\"https://or\","
                + "\"OPENCODE_PASSWORD\":\"harness-pw\"}}");
    assertThat(response.statusCode()).isEqualTo(201);
    var vm = JSON.readValue(response.body(), VmDto.class);
    var secrets = "/" + vm.id() + "/secrets";
    assertThat(VAULT.entries.get("workspaced-ai/vms/" + vm.id() + "/system"))
        .containsOnlyKeys("OPENROUTER_BASE_URL", "OPENCODE_PASSWORD");
    assertThat(
            send("POST", secrets, "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_x\"}").statusCode())
        .isEqualTo(201);
    for (var name : new String[] {"OPENROUTER_BASE_URL", "OPENCODE_CONFIG"}) {
      var refused = send("POST", secrets, "{\"name\":\"" + name + "\",\"value\":\"v\"}");
      assertThat(refused.statusCode()).isEqualTo(409);
      assertThat(refused.body()).contains(name);
    }
    assertThat(send("GET", secrets, null).body()).doesNotContain("OPENROUTER_BASE_URL", "OPENCODE");

    var harness = send("GET", "/" + vm.id() + "/harness", null);

    assertThat(harness.statusCode()).isEqualTo(200);
    var body = JSON.readValue(harness.body(), HarnessDto.class);
    assertThat(body.image()).isEqualTo(IMAGE);
    assertThat(body.environment())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "OPENROUTER_API_KEY", "sk-or-v1-test",
                "GITHUB_TOKEN", "ghp_x",
                "OPENROUTER_BASE_URL", "https://or",
                "OPENCODE_PASSWORD", "harness-pw"));

    send("DELETE", "/" + vm.id(), null);
    assertThat(VAULT.entries)
        .doesNotContainKeys(
            "workspaced-ai/vms/" + vm.id(), "workspaced-ai/vms/" + vm.id() + "/system");
  }

  @Test
  void givesAVmCreatedWithoutAnEnvironmentAPassword() throws Exception {
    var vm = create();

    var first =
        JSON.readValue(send("GET", "/" + vm.id() + "/harness", null).body(), HarnessDto.class);
    var second =
        JSON.readValue(send("GET", "/" + vm.id() + "/harness", null).body(), HarnessDto.class);

    assertThat(first.environment().get("OPENCODE_PASSWORD"))
        .isNotBlank()
        .isEqualTo(second.environment().get("OPENCODE_PASSWORD"));
  }

  @Test
  void rejectsAnInvalidEnvironment() throws Exception {
    for (var environment :
        new String[] {"{\"1BAD\":\"v\"}", "{\"A-B\":\"v\"}", "{\"OK\":null}", "[]"}) {
      var response =
          send(
              "POST",
              "",
              "{\"workspaceId\":\""
                  + UUID.randomUUID()
                  + "\",\"userId\":\""
                  + USER
                  + KEY
                  + ",\"environment\":"
                  + environment
                  + "}");
      assertThat(response.statusCode()).as(environment).isEqualTo(400);
    }
  }

  @Test
  void keepsTheKeyInVaultAndManagesTheRestOfItsSecrets() throws Exception {
    var vm = create();
    var secrets = "/" + vm.id() + "/secrets";
    assertThat(VAULT.entries.get("workspaced-ai/vms/" + vm.id()))
        .containsEntry("OPENROUTER_API_KEY", "sk-or-v1-test");

    var added = send("POST", secrets, "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_x\"}");
    assertThat(added.statusCode()).isEqualTo(201);
    assertThat(added.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith(secrets + "/GITHUB_TOKEN"));
    assertThat(added.body()).doesNotContain("ghp_x");
    assertThat(send("POST", secrets, "{\"name\":\"GITHUB_TOKEN\",\"value\":\"y\"}").statusCode())
        .isEqualTo(409);

    var listed = send("GET", secrets, null);
    assertThat(listed.body())
        .isEqualTo(
            "[{\"name\":\"OPENROUTER_API_KEY\",\"system\":true},"
                + "{\"name\":\"GITHUB_TOKEN\",\"system\":false}]");

    assertThat(send("DELETE", secrets + "/OPENROUTER_API_KEY", null).statusCode()).isEqualTo(409);
    assertThat(send("DELETE", secrets + "/GITHUB_TOKEN", null).statusCode()).isEqualTo(204);
    assertThat(send("DELETE", secrets + "/GITHUB_TOKEN", null).statusCode()).isEqualTo(404);

    assertThat(send("DELETE", "/" + vm.id(), null).statusCode()).isEqualTo(204);
    assertThat(VAULT.entries).doesNotContainKey("workspaced-ai/vms/" + vm.id());
    assertThat(send("GET", secrets, null).statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsAnInvalidSecret() throws Exception {
    var secrets = "/" + create().id() + "/secrets";
    for (var body :
        new String[] {
          "{\"name\":\"1BAD\",\"value\":\"v\"}",
          "{\"name\":\"A-B\",\"value\":\"v\"}",
          "{\"name\":\"OK\",\"value\":\"\"}"
        }) {
      assertThat(send("POST", secrets, body).statusCode()).isEqualTo(400);
    }
  }

  @Test
  void createsNoVmWithoutAKey() throws Exception {
    var response =
        send(
            "POST",
            "",
            "{\"workspaceId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + USER + "\"}");
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void rejectsAMissingOrMalformedWorkspaceId() throws Exception {
    var valid = "\"userId\":\"" + USER + KEY;
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
                + KEY
                + ",\"serverType\":\"cx53\"}");
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
                + KEY
                + ",\"serverType\":\"cx33\"}");
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
            "{\"workspaceId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + USER + KEY + "}");
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
