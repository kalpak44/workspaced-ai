package io.github.kalpak44.workspacedai.vmmanager.vault;

import io.github.kalpak44.workspacedai.vmmanager.vm.SecretStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps each VM's secrets as one KV version 2 entry, {@code <prefix>/<vm id>}, with one key per
 * secret. One entry rather than one per secret, so a change is a single check-and-set write and two
 * concurrent adds cannot both slip under the limit.
 */
@Component
public class VaultSecretStore implements SecretStore {

  // Each attempt loses only to a write that landed in between, so a few always suffice.
  private static final int ATTEMPTS = 5;

  private final VaultProperties properties;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;
  private String token;
  private Instant tokenExpiresAt = Instant.MIN;

  public VaultSecretStore(VaultProperties properties, JsonMapper json, Clock clock) {
    this.properties = properties;
    this.json = json;
    this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
  }

  @Override
  public Set<String> names(UUID vmId) {
    return read(vmId).data().keySet();
  }

  @Override
  public void update(UUID vmId, UnaryOperator<Map<String, String>> change) {
    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      var current = read(vmId);
      var next = change.apply(Map.copyOf(current.data()));
      if (current.version() == 0) {
        keepOneVersion(vmId);
      }
      var body = json.createObjectNode();
      body.putObject("options").put("cas", current.version());
      var data = body.putObject("data");
      next.forEach(data::put);
      var response = send("POST", "/data/", vmId, body.toString());
      if (response.statusCode() == 200 || response.statusCode() == 204) {
        return;
      }
      if (!casMismatch(response)) {
        throw failed(response);
      }
    }
    throw new VaultException("the secrets of VM " + vmId + " kept changing; try again");
  }

  /** The metadata rather than the data, so the values in earlier versions go too. */
  @Override
  public void deleteAll(UUID vmId) {
    var response = send("DELETE", "/metadata/", vmId, null);
    if (response.statusCode() != 204 && response.statusCode() != 404) {
      throw failed(response);
    }
  }

  // A removed secret would otherwise stay readable in the entry's history.
  private void keepOneVersion(UUID vmId) {
    var response = send("POST", "/metadata/", vmId, "{\"max_versions\":1}");
    if (response.statusCode() != 204 && response.statusCode() != 200) {
      throw failed(response);
    }
  }

  private Entry read(UUID vmId) {
    var response = send("GET", "/data/", vmId, null);
    if (response.statusCode() == 404) {
      return new Entry(Map.of(), 0);
    }
    if (response.statusCode() != 200) {
      throw failed(response);
    }
    var body = parse(response);
    var data = new HashMap<String, String>();
    body.path("data")
        .path("data")
        .properties()
        .forEach(e -> data.put(e.getKey(), e.getValue().asString()));
    return new Entry(data, body.path("data").path("metadata").path("version").asInt());
  }

  private static boolean casMismatch(HttpResponse<String> response) {
    return response.statusCode() == 400 && response.body().contains("check-and-set");
  }

  /** A token that expired or was revoked early gets one fresh sign-in before the call fails. */
  private HttpResponse<String> send(String method, String kind, UUID vmId, String body) {
    if (!properties.isConfigured()) {
      throw new VaultException.NotConfigured();
    }
    var path = "/v1/" + properties.kvMount() + kind + properties.prefix() + "/" + vmId;
    var response = call(method, path, body, token());
    if (response.statusCode() == 403 && !properties.hasToken()) {
      tokenExpiresAt = Instant.MIN;
      response = call(method, path, body, token());
    }
    return response;
  }

  private synchronized String token() {
    if (properties.hasToken()) {
      return properties.token();
    }
    if (clock.instant().isBefore(tokenExpiresAt)) {
      return token;
    }
    String jwt;
    try {
      jwt = Files.readString(properties.jwtPath()).strip();
    } catch (IOException e) {
      throw new VaultException("could not read the service account token to sign in to Vault", e);
    }
    var body = json.createObjectNode();
    body.put("role", properties.role());
    body.put("jwt", jwt);
    var response =
        call("POST", "/v1/auth/" + properties.authPath() + "/login", body.toString(), null);
    if (response.statusCode() != 200) {
      throw failed(response);
    }
    var auth = parse(response).path("auth");
    token = auth.path("client_token").asString();
    // Signs in again well before the lease runs out, so no call races its expiry.
    tokenExpiresAt = clock.instant().plusSeconds(auth.path("lease_duration").asLong() * 3 / 4);
    return token;
  }

  private HttpResponse<String> call(String method, String path, String body, String vaultToken) {
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
    if (vaultToken != null) {
      request.header("X-Vault-Token", vaultToken);
    }
    try {
      return http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new VaultException("could not reach Vault on " + method + " " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VaultException("interrupted on " + method + " " + path + " at Vault", e);
    }
  }

  private JsonNode parse(HttpResponse<String> response) {
    try {
      return json.readTree(response.body());
    } catch (JacksonException e) {
      throw new VaultException("Vault sent a body that is not JSON", e);
    }
  }

  // The path only: Vault's error bodies never carry a value, but the request body would.
  private static VaultException failed(HttpResponse<String> response) {
    return new VaultException(
        "Vault answered "
            + response.statusCode()
            + " on "
            + response.request().method()
            + " "
            + response.request().uri().getPath());
  }

  private record Entry(Map<String, String> data, int version) {}
}
