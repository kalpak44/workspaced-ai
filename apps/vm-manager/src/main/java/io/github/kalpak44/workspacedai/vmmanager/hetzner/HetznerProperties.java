package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param token a Read &amp; Write API token of the Hetzner Cloud project VMs are created in; blank
 *     leaves the app running but unable to reach Hetzner
 * @param baseUrl the Hetzner Cloud API, overridden only by tests
 * @param location the Hetzner location VMs are created in, and whose prices are shown; nbg1 is
 *     Nuremberg, Germany
 * @param image the operating system image every VM starts from
 * @param sshKeys names or ids of SSH keys in the project to install on every VM; with none, Hetzner
 *     emails the account a root password instead
 * @param timeout how long one call to Hetzner may take, connecting included
 * @param imageSelector a label selector, e.g. {@code image=workspace-vm}: when set, every VM is
 *     created from the newest snapshot carrying it instead of {@code image}
 * @param fallbackLocations tried in order for a server type sold out in {@code location}, so a plan
 *     stays on the same server rather than going unavailable
 */
@ConfigurationProperties("workspaced.hetzner")
public record HetznerProperties(
    String token,
    @DefaultValue("https://api.hetzner.cloud/v1") URI baseUrl,
    @DefaultValue("nbg1") String location,
    @DefaultValue("ubuntu-24.04") String image,
    @DefaultValue List<String> sshKeys,
    @DefaultValue("10s") Duration timeout,
    String imageSelector,
    @DefaultValue List<String> fallbackLocations) {

  /** The preferred location first, then the fallbacks. */
  public List<String> locations() {
    var all = new ArrayList<String>();
    all.add(location);
    fallbackLocations.stream().filter(other -> !all.contains(other)).forEach(all::add);
    return List.copyOf(all);
  }

  boolean hasToken() {
    return token != null && !token.isBlank();
  }

  boolean hasImageSelector() {
    return imageSelector != null && !imageSelector.isBlank();
  }
}
