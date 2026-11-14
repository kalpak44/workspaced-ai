package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param token a Read &amp; Write API token of the Hetzner Cloud project VMs are created in; blank
 *     leaves the app running but unable to reach Hetzner
 * @param baseUrl the Hetzner Cloud API, overridden only by tests
 * @param location the Hetzner location VMs are created in, and whose prices are shown
 */
@ConfigurationProperties("workspaced.hetzner")
public record HetznerProperties(
    String token,
    @DefaultValue("https://api.hetzner.cloud/v1") URI baseUrl,
    @DefaultValue("nbg1") String location) {

  boolean hasToken() {
    return token != null && !token.isBlank();
  }
}
