package io.github.kalpak44.workspacedai.api.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Calls the provider's own key endpoint with the key as a bearer token. */
@Component
public class HttpApiKeyCheck implements ApiKeyCheck {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  @Override
  public boolean accepts(ModelProvider provider, String apiKey) {
    try {
      return accepts(URI.create(provider.keyCheckUrl()), apiKey);
    } catch (IOException | IllegalStateException e) {
      throw new ProviderUnreachableException(provider, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderUnreachableException(provider, e);
    }
  }

  /** Any other status is the provider failing, not the key, so it is never read as a refusal. */
  boolean accepts(URI url, String apiKey) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(url)
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .GET()
            .build();
    var status = http.send(request, BodyHandlers.discarding()).statusCode();
    if (status == 401 || status == 403) {
      return false;
    }
    if (status / 100 == 2) {
      return true;
    }
    throw new IllegalStateException("key check answered " + status);
  }
}
