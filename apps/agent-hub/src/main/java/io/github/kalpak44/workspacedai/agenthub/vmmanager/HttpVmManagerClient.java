package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Calls the VM manager's routes over HTTP; it is internal, so no credential is sent. */
@Component
public class HttpVmManagerClient implements VmManagerClient {

  private final AgentHubProperties properties;
  private final HttpClient http;
  private final JsonMapper json;

  public HttpVmManagerClient(AgentHubProperties properties, HttpClient http, JsonMapper json) {
    this.properties = properties;
    this.http = http;
    this.json = json;
  }

  @Override
  public void ready(UUID vmId) {
    send(vmId, "POST", "ready", null);
  }

  @Override
  public void ready(UUID vmId, Long revision) {
    if (revision == null) ready(vmId);
    else send(vmId, "POST", "ready-binding", json.writeValueAsString(Map.of("revision", revision)));
  }

  @Override
  public Heartbeat heartbeat(UUID vmId, VmMemory memory) {
    return heartbeat(vmId, memory, null);
  }

  @Override
  public Heartbeat heartbeat(
      UUID vmId, VmMemory memory, tools.jackson.databind.JsonNode supervisor) {
    var values = new java.util.HashMap<String, Object>();
    if (memory != null) values.put("memory", memory);
    if (supervisor != null && supervisor.isObject()) values.put("supervisor", supervisor);
    var report = values.isEmpty() ? null : json.writeValueAsString(values);
    var body = send(vmId, "POST", "heartbeat", report);
    try {
      var answer = json.readTree(body);
      var status = answer.path("status");
      if (status.isString()) {
        var firewall = answer.path("firewall");
        return new Heartbeat(status.asString(), firewall.isArray() ? firewall : null);
      }
    } catch (JacksonException e) {
      throw new VmManagerException("VM manager answered a heartbeat that is not JSON", e);
    }
    throw new VmManagerException("VM manager answered a heartbeat without a status");
  }

  @Override
  public byte[] harness(UUID vmId) {
    return send(vmId, "GET", "harness", null);
  }

  // Only the status goes into a failure: the harness body carries the VM's secret values.
  private byte[] send(UUID vmId, String method, String route, String body) {
    var base = properties.vmManagerUrl().toString().replaceAll("/+$", "");
    var request =
        HttpRequest.newBuilder(URI.create(base + "/vms/" + vmId + "/" + route))
            .timeout(properties.vmManagerTimeout())
            .header("Accept", "application/json");
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(request.build(), BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new VmManagerException("VM manager unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VmManagerException("interrupted calling the VM manager", e);
    }
    var status = response.statusCode();
    if (status == 404) {
      throw new VmNotFoundException(vmId);
    }
    if (status == 503) {
      throw new VmManagerException.Unavailable("VM manager is unavailable for " + route);
    }
    if (status != 200) {
      throw new VmManagerException("VM manager answered " + status + " to " + route);
    }
    return response.body();
  }
}
