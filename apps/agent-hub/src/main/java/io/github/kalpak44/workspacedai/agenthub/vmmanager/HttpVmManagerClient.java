package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Calls the VM manager's routes over HTTP; it is internal, so no credential is sent. */
@Component
public class HttpVmManagerClient implements VmManagerClient {

  private final AgentHubProperties properties;
  private final HttpClient http;

  public HttpVmManagerClient(AgentHubProperties properties, HttpClient http) {
    this.properties = properties;
    this.http = http;
  }

  @Override
  public void ready(UUID vmId) {
    post(vmId, "ready", 200);
  }

  @Override
  public void heartbeat(UUID vmId) {
    post(vmId, "heartbeat", 204);
  }

  private void post(UUID vmId, String report, int expected) {
    var base = properties.vmManagerUrl().toString().replaceAll("/+$", "");
    var request =
        HttpRequest.newBuilder(URI.create(base + "/vms/" + vmId + "/" + report))
            .timeout(properties.vmManagerTimeout())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    int status;
    try {
      status = http.send(request, BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      throw new VmManagerException("VM manager unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VmManagerException("interrupted calling the VM manager", e);
    }
    if (status == 404) {
      throw new VmNotFoundException(vmId);
    }
    if (status != expected) {
      throw new VmManagerException("VM manager answered " + status + " to " + report);
    }
  }
}
