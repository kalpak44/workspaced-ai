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
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps each VM's secrets as two KV version 2 entries, {@code <prefix>/<vm id>} for the user's and
 * {@code <prefix>/<vm id>/system} for the harness's, with one key per value. One entry rather than
 * one per secret, so a change is a single check-and-set write and two concurrent adds cannot both
 * slip under the limit.
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
  public Map<String, String> read(UUID vmId, Entry entry) {
    return Map.copyOf(read(path(vmId, entry)).data());
  }

  @Override
  public Map<String, String> update(
      UUID vmId, Entry entry, UnaryOperator<Map<String, String>> change) {
    var path = path(vmId, entry);
    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      var current = read(path);
      var next = Map.copyOf(change.apply(Map.copyOf(current.data())));
      if (current.version() != 0 && next.equals(current.data())) {
        return next;
      }
      if (current.version() == 0) {
        keepOneVersion(path);
      }
      var body = json.createObjectNode();
      body.putObject("options").put("cas", current.version());
      var data = body.putObject("data");
      next.forEach(data::put);
      var response = send("POST", "/data/", path, body.toString());
      if (response.statusCode() == 200 || response.statusCode() == 204) {
        return next;
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
    for (var entry : Entry.values()) {
      var response = send("DELETE", "/metadata/", path(vmId, entry), null);
      if (response.statusCode() != 204 && response.statusCode() != 404) {
        throw failed(response);
      }
    }
  }

  private String path(UUID vmId, Entry entry) {
    var path = properties.prefix() + "/" + vmId;
    return entry == Entry.SYSTEM ? path + "/system" : path;
  }

  // A removed secret would otherwise stay readable in the entry's history.
  private void keepOneVersion(String path) {
    var response = send("POST", "/metadata/", path, "{\"max_versions\":1}");
    if (response.statusCode() != 204 && response.statusCode() != 200) {
      throw failed(response);
    }
  }

  private Stored read(String path) {
    var response = send("GET", "/data/", path, null);
    if (response.statusCode() == 404) {
      return new Stored(Map.of(), 0);
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
    return new Stored(data, body.path("data").path("metadata").path("version").asInt());
  }

  private static boolean casMismatch(HttpResponse<String> response) {
    return response.statusCode() == 400 && response.body().contains("check-and-set");
  }

  /** A token that expired or was revoked early gets one fresh sign-in before the call fails. */
  private HttpResponse<String> send(String method, String kind, String entry, String body) {
    if (!properties.isConfigured()) {
      throw new VaultException.NotConfigured();
    }
    var path = "/v1/" + properties.kvMount() + kind + entry;
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

  private record Stored(Map<String, String> data, int version) {

    // Names only, so a stray log line cannot carry a value.
    @Override
    public String toString() {
      return "Stored[names=" + data.keySet() + ", version=" + version + "]";
    }
  }
}
