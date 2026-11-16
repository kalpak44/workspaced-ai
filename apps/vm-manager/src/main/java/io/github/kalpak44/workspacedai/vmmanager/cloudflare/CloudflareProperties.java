package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param token an API token limited to DNS edit on the zone; blank leaves the app running with no
 *     VM names published
 * @param zoneId the zone VM names are published in
 * @param baseUrl the Cloudflare API, overridden only by tests
 * @param timeout how long one call to Cloudflare may take, connecting included
 */
@ConfigurationProperties("workspaced.cloudflare")
public record CloudflareProperties(
    String token,
    String zoneId,
    @DefaultValue("https://api.cloudflare.com/client/v4") URI baseUrl,
    @DefaultValue("10s") Duration timeout) {

  boolean isConfigured() {
    return token != null && !token.isBlank() && zoneId != null && !zoneId.isBlank();
  }
}
