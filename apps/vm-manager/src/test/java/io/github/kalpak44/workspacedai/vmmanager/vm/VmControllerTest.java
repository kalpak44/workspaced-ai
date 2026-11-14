package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class VmControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void createsOneVmPerOwner() throws Exception {
    var owner = UUID.randomUUID().toString();
    var first = send("POST", "", "{\"ownerRef\":\"" + owner + "\"}");
    assertThat(first.statusCode()).isEqualTo(201);
    var vm = JSON.readValue(first.body(), Vm.class);
    assertThat(vm.status()).isEqualTo(VmStatus.PROVISIONING);
    assertThat(vm.serverType()).isEqualTo("cx23");
    assertThat(first.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/vms/" + vm.id()));

    var again = send("POST", "", "{\"ownerRef\":\"" + owner + "\",\"serverType\":\"cx33\"}");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(again.body(), Vm.class).id()).isEqualTo(vm.id());

    var listed = send("GET", "?ownerRef=" + owner, null);
    assertThat(JSON.readValue(listed.body(), new TypeReference<List<Vm>>() {}))
        .extracting(Vm::id)
        .containsExactly(vm.id());
  }

  @Test
  void followsReadyRestartAndHeartbeat() throws Exception {
    var vm = create();
    assertThat(vm.lastSeenAt()).isNull();

    var ready = JSON.readValue(send("POST", "/" + vm.id() + "/ready", null).body(), Vm.class);
    assertThat(ready.status()).isEqualTo(VmStatus.READY);
    assertThat(ready.lastSeenAt()).isNotNull();

    var restart = send("POST", "/" + vm.id() + "/restart", null);
    assertThat(restart.statusCode()).isEqualTo(202);
    assertThat(JSON.readValue(restart.body(), Vm.class).status()).isEqualTo(VmStatus.RESTARTING);

    assertThat(send("POST", "/" + vm.id() + "/heartbeat", null).statusCode()).isEqualTo(204);
    var fetched = JSON.readValue(send("GET", "/" + vm.id(), null).body(), Vm.class);
    assertThat(fetched.status()).isEqualTo(VmStatus.RESTARTING);
    assertThat(fetched.lastSeenAt()).isAfterOrEqualTo(ready.lastSeenAt());
  }

  @Test
  void deletesAndThenNoLongerFindsIt() throws Exception {
    var vm = create();
    assertThat(send("DELETE", "/" + vm.id(), null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/" + vm.id(), null).statusCode()).isEqualTo(404);
    assertThat(send("DELETE", "/" + vm.id(), null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/" + vm.id() + "/ready", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/" + vm.id() + "/restart", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/" + vm.id() + "/heartbeat", null).statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsBlankOwner() throws Exception {
    var response = send("POST", "", "{\"ownerRef\":\"  \"}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
  }

  @Test
  void rejectsAServerTypeNotOffered() throws Exception {
    var response =
        send("POST", "", "{\"ownerRef\":\"" + UUID.randomUUID() + "\",\"serverType\":\"cx53\"}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("cx23, cx33");
  }

  @Test
  void createsTheSizeAsked() throws Exception {
    var response =
        send("POST", "", "{\"ownerRef\":\"" + UUID.randomUUID() + "\",\"serverType\":\"cx33\"}");
    assertThat(JSON.readValue(response.body(), Vm.class).serverType()).isEqualTo("cx33");
  }

  @Test
  void listsNoOptionsWithoutAToken() throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base().replace("/vms", "/vm-options"))).build();
    var response = http.send(request, BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("no Hetzner API token");
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

  private Vm create() throws Exception {
    var response = send("POST", "", "{\"ownerRef\":\"" + UUID.randomUUID() + "\"}");
    return JSON.readValue(response.body(), Vm.class);
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
