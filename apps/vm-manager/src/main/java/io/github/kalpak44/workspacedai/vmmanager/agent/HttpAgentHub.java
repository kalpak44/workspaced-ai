package io.github.kalpak44.workspacedai.vmmanager.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the agent hub's internal routes over HTTP inside the cluster, with this pod's service
 * account token, which the hub accepts on the enrollment routes alone.
 */
@Component
public class HttpAgentHub implements AgentHub {

  private static final String TOKEN_HEADER = "X-Agent-Hub-Token";

  private final AgentHubProperties properties;
  private final JsonMapper json;
  private final HttpClient http;

  public HttpAgentHub(AgentHubProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
  }

  /**
   * The token is readable from the VM's metadata for as long as the VM lives, which is why it is
   * single use and short-lived: once the supervisor has enrolled it opens nothing.
   */
  @Override
  public Optional<String> bootstrap(UUID vmId) {
    if (!properties.isConfigured()) {
      return Optional.empty();
    }
    var body = json.createObjectNode().put("vmId", vmId.toString()).toString();
    var response = send("POST", "/internal/enrollments", body);
    if (response.statusCode() != 201) {
      throw failed(response);
    }
    try {
      var token = json.readTree(response.body()).path("token");
      if (!token.isString() || token.asString().isBlank()) {
        throw new AgentHubException("the agent hub issued no enrollment token");
      }
      return Optional.of(cloudConfig(vmId, token.asString()));
    } catch (JacksonException e) {
      throw new AgentHubException("the agent hub sent a body that is not JSON", e);
    }
  }

  @Override
  public void revoke(UUID vmId) {
    if (!properties.isConfigured()) {
      return;
    }
    var response = send("DELETE", "/internal/agents/" + vmId, null);
    if (response.statusCode() != 204) {
      throw failed(response);
    }
  }

  // Root-only, since the token in it enrolls the VM; the supervisor drops it once used.
  private String cloudConfig(UUID vmId, String token) {
    return """
        #cloud-config
        write_files:
          - path: /etc/workspaced/agent.env
            owner: root:root
            permissions: "0600"
            content: |
              WORKSPACED_VM_ID=%s
              WORKSPACED_AGENT_URL=%s
              WORKSPACED_ENROLLMENT_TOKEN=%s
        """
        .formatted(vmId, properties.publicUrl(), token);
  }

  private HttpResponse<String> send(String method, String path, String body) {
    var base = properties.url().toString().replaceAll("/+$", "");
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Accept", "application/json")
            .timeout(properties.timeout())
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    callerToken().ifPresent(token -> request.header(TOKEN_HEADER, token));
    try {
      return http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AgentHubException("could not reach the agent hub on " + method + " " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentHubException("interrupted on " + method + " " + path, e);
    }
  }

  // Absent outside a cluster, where the hub refuses the call and says so.
  private Optional<String> callerToken() {
    try {
      return Optional.of(Files.readString(properties.tokenFile()).strip());
    } catch (IOException _) {
      return Optional.empty();
    }
  }

  private static AgentHubException failed(HttpResponse<String> response) {
    return new AgentHubException(
        "the agent hub answered "
            + response.statusCode()
            + " on "
            + response.request().method()
            + " "
            + response.request().uri().getPath());
  }
}
