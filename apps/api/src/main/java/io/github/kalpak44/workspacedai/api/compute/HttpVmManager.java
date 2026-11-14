package io.github.kalpak44.workspacedai.api.compute;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the VM manager over HTTP inside the cluster; it needs no credential, being private. */
@Component
public class HttpVmManager implements VmManager {

  private static final Logger log = LoggerFactory.getLogger(HttpVmManager.class);
  private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

  private final VmManagerProperties properties;
  private final JsonMapper json;
  private final HttpClient http;

  public HttpVmManager(VmManagerProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
  }

  @Override
  public List<VmSize> sizes() {
    var response = send(get("/vm-options"));
    expect(response, 200);
    var sizes = new ArrayList<VmSize>();
    for (var option : read(response)) {
      sizes.add(
          new VmSize(
              option.path("serverType").asString(),
              option.path("isDefault").asBoolean(),
              option.path("cores").asInt(),
              option.path("memoryGb").asDouble(),
              option.path("diskGb").asInt(),
              option.path("cpuType").asString(),
              option.path("architecture").asString(),
              option.path("location").asString(),
              option.path("monthlyNet").decimalValue(),
              option.path("monthlyGross").decimalValue()));
    }
    return List.copyOf(sizes);
  }

  @Override
  public List<ProvisionedVm> vms() {
    var response = send(get("/vms"));
    expect(response, 200);
    var vms = new ArrayList<ProvisionedVm>();
    read(response).forEach(vm -> vms.add(vm(vm)));
    return List.copyOf(vms);
  }

  @Override
  public ProvisionedVm create(
      UUID workspaceId,
      UUID userId,
      String serverType,
      String openRouterApiKey,
      Map<String, String> environment) {
    var body = json.createObjectNode();
    body.put("workspaceId", workspaceId.toString());
    body.put("userId", userId.toString());
    body.put("openRouterApiKey", openRouterApiKey);
    var variables = body.putObject("environment");
    environment.forEach(variables::put);
    if (serverType != null && !serverType.isBlank()) {
      body.put("serverType", serverType.strip());
    }
    var response =
        send(
            request("/vms")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    if (response.statusCode() == 400) {
      throw new ServerTypeRejectedException(detail(response));
    }
    if (response.statusCode() != 200) {
      expect(response, 201);
    }
    return vm(read(response));
  }

  /** Lists by workspace rather than trusting a stored id, so a VM orphaned mid-create goes too. */
  @Override
  public void deleteFor(UUID workspaceId) {
    var listed = send(get("/vms?workspaceId=" + workspaceId));
    expect(listed, 200);
    for (var vm : read(listed)) {
      var deleted = send(request("/vms/" + vm.path("id").asString()).DELETE());
      if (deleted.statusCode() != 404) {
        expect(deleted, 204);
      }
    }
  }

  @Override
  public void restartFor(UUID workspaceId) {
    var vm =
        vmIdOf(workspaceId)
            .orElseThrow(
                () -> new IllegalStateException("workspace " + workspaceId + " has no VM"));
    var restarted =
        send(request("/vms/" + vm + "/restart").POST(HttpRequest.BodyPublishers.noBody()));
    expect(restarted, 202);
  }

  @Override
  public void putSecret(UUID workspaceId, String name, String value) {
    var vm =
        vmIdOf(workspaceId)
            .orElseThrow(
                () -> new IllegalStateException("workspace " + workspaceId + " has no VM"));
    var body = json.createObjectNode();
    body.put("name", name);
    body.put("value", value);
    var response =
        send(
            request("/vms/" + vm + "/secrets")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    if (response.statusCode() == 409) {
      throw new VmSecretRefusedException(detail(response));
    }
    expect(response, 201);
  }

  @Override
  public void removeSecret(UUID workspaceId, String name) {
    var vm = vmIdOf(workspaceId);
    if (vm.isEmpty()) {
      return;
    }
    var path = "/vms/" + vm.get() + "/secrets/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
    var response = send(request(path).DELETE());
    if (response.statusCode() != 404) {
      expect(response, 204);
    }
  }

  private Optional<String> vmIdOf(UUID workspaceId) {
    var listed = send(get("/vms?workspaceId=" + workspaceId));
    expect(listed, 200);
    var vms = read(listed);
    return vms.isEmpty() ? Optional.empty() : Optional.of(vms.get(0).path("id").asString());
  }

  /**
   * Opens the VM manager's event stream and dispatches each status event. The stream starts with
   * every VM, so a reconnect also catches up on whatever changed while it was down.
   */
  @Override
  public void watch(Consumer<ProvisionedVm> listener) {
    if (!properties.isConfigured()) {
      return;
    }
    Thread.ofVirtual()
        .name("vm-manager-events")
        .start(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  stream(listener);
                } catch (IOException | RuntimeException e) {
                  log.warn("VM manager event stream dropped: {}", e.getMessage());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  Thread.sleep(RECONNECT_DELAY);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
  }

  // No request timeout: the stream stays open for as long as the VM manager runs.
  private void stream(Consumer<ProvisionedVm> listener) throws IOException, InterruptedException {
    var base = properties.url().toString().replaceAll("/+$", "");
    var request =
        HttpRequest.newBuilder(URI.create(base + "/vms/events"))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    var response = http.send(request, BodyHandlers.ofLines());
    if (response.statusCode() != 200) {
      response.body().close();
      throw new VmManagerFailedException("answered " + response.statusCode() + " to the events");
    }
    try (var lines = response.body()) {
      var event = new String[] {""};
      var data = new StringBuilder();
      lines.forEach(
          line -> {
            if (line.startsWith("event:")) {
              event[0] = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
              data.append(line.substring(5));
            } else if (line.isEmpty()) {
              if (event[0].equals("status") && !data.isEmpty()) {
                listener.accept(vm(json.readTree(data.toString())));
              }
              event[0] = "";
              data.setLength(0);
            }
          });
    }
  }

  private static ProvisionedVm vm(JsonNode vm) {
    return new ProvisionedVm(
        UUID.fromString(vm.path("id").asString()),
        UUID.fromString(vm.path("workspaceId").asString()),
        vm.path("serverType").asString(),
        vm.path("status").asString());
  }

  private HttpRequest.Builder get(String path) {
    return request(path).GET();
  }

  private HttpRequest.Builder request(String path) {
    if (!properties.isConfigured()) {
      throw new VmManagerUnavailableException("no VM manager is configured", null);
    }
    var base = properties.url().toString().replaceAll("/+$", "");
    return HttpRequest.newBuilder(URI.create(base + path))
        .header("Accept", "application/json")
        .timeout(properties.timeout());
  }

  private HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new VmManagerUnavailableException("the VM manager cannot be reached", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VmManagerUnavailableException("interrupted while calling the VM manager", e);
    }
  }

  // The VM manager answers 503 itself while Hetzner is unreachable or its inventory is loading.
  private void expect(HttpResponse<String> response, int status) {
    if (response.statusCode() == 503) {
      throw new VmManagerUnavailableException(detail(response), null);
    }
    if (response.statusCode() != status) {
      throw new VmManagerFailedException(
          "answered "
              + response.statusCode()
              + " to "
              + response.request().method()
              + " "
              + response.request().uri().getPath()
              + ": "
              + detail(response));
    }
  }

  private JsonNode read(HttpResponse<String> response) {
    try {
      return json.readTree(response.body());
    } catch (JacksonException e) {
      throw new VmManagerFailedException("sent a body that is not JSON");
    }
  }

  /** The problem detail's own explanation, so the reason reaches the user unchanged. */
  private String detail(HttpResponse<String> response) {
    try {
      var detail = json.readTree(response.body()).path("detail");
      return detail.isString() ? detail.asString() : "status " + response.statusCode();
    } catch (JacksonException e) {
      return "status " + response.statusCode();
    }
  }
}
