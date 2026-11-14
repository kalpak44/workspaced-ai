package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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
    send(vmId, "POST", "ready");
  }

  @Override
  public String heartbeat(UUID vmId) {
    var body = send(vmId, "POST", "heartbeat");
    try {
      var status = json.readTree(body).path("status");
      if (status.isString()) {
        return status.asString();
      }
    } catch (JacksonException e) {
      throw new VmManagerException("VM manager answered a heartbeat that is not JSON", e);
    }
    throw new VmManagerException("VM manager answered a heartbeat without a status");
  }

  @Override
  public byte[] harness(UUID vmId) {
    return send(vmId, "GET", "harness");
  }

  // Only the status goes into a failure: the harness body carries the VM's secret values.
  private byte[] send(UUID vmId, String method, String route) {
    var base = properties.vmManagerUrl().toString().replaceAll("/+$", "");
    var request =
        HttpRequest.newBuilder(URI.create(base + "/vms/" + vmId + "/" + route))
            .timeout(properties.vmManagerTimeout())
            .header("Accept", "application/json")
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, BodyHandlers.ofByteArray());
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
