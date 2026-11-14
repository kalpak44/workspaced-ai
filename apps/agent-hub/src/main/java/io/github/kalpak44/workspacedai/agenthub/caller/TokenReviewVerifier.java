package io.github.kalpak44.workspacedai.agenthub.caller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the Kubernetes API server whether a token is valid for this hub's audience, with this pod's
 * own service account token, which may create TokenReviews and nothing else. An answer is kept for
 * a minute, since the callers send the same token on every request until the kubelet rotates it.
 */
final class TokenReviewVerifier implements CallerVerifier {

  private static final Logger log = LoggerFactory.getLogger(TokenReviewVerifier.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  // Each caller pod holds one token at a time, so this is only reached by junk.
  private static final int MAX_CACHED = 1000;

  private record Accepted(String username, Instant until) {}

  private final CallerProperties properties;
  private final URI reviews;
  private final HttpClient http;
  private final JsonMapper json;
  private final Clock clock;
  private final Map<String, Accepted> cache = new ConcurrentHashMap<>();

  TokenReviewVerifier(CallerProperties properties, URI apiServer, JsonMapper json, Clock clock)
      throws IOException, GeneralSecurityException {
    this.properties = properties;
    this.reviews = apiServer.resolve("/apis/authentication.k8s.io/v1/tokenreviews");
    this.json = json;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .sslContext(trusting(properties.serviceAccountDir().resolve("ca.crt")))
            .build();
  }

  @Override
  public Optional<String> verify(String token) {
    var key = sha256(token);
    var now = clock.instant();
    var cached = cache.get(key);
    if (cached != null && now.isBefore(cached.until())) {
      return Optional.of(cached.username());
    }
    var username = review(token);
    if (username.isPresent()) {
      if (cache.size() >= MAX_CACHED) {
        cache.clear();
      }
      cache.put(key, new Accepted(username.get(), now.plus(properties.cacheFor())));
    }
    return username;
  }

  private Optional<String> review(String token) {
    var body =
        Map.of(
            "apiVersion", "authentication.k8s.io/v1",
            "kind", "TokenReview",
            "spec", Map.of("token", token, "audiences", List.of(properties.audience())));
    try {
      var request =
          HttpRequest.newBuilder(reviews)
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + ownToken())
              .header("Content-Type", "application/json")
              .POST(BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 201 && response.statusCode() != 200) {
        log.warn("TokenReview answered {}", response.statusCode());
        return Optional.empty();
      }
      var status = json.readTree(response.body()).path("status");
      var audiences = status.path("audiences");
      var forThisHub = false;
      for (var audience : audiences) {
        forThisHub |= properties.audience().equals(audience.asString());
      }
      if (!status.path("authenticated").asBoolean(false) || !forThisHub) {
        return Optional.empty();
      }
      return Optional.of(status.path("user").path("username").asString());
    } catch (IOException e) {
      log.warn("TokenReview failed: {}", e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  // Read afresh each time: the kubelet rotates it.
  private String ownToken() throws IOException {
    return Files.readString(properties.serviceAccountDir().resolve("token")).strip();
  }

  private static SSLContext trusting(java.nio.file.Path caFile)
      throws IOException, GeneralSecurityException {
    var store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    try (InputStream in = Files.newInputStream(caFile)) {
      var i = 0;
      for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
        store.setCertificateEntry("ca-" + i++, cert);
      }
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    return context;
  }

  private static String sha256(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
