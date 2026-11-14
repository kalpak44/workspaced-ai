package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.net.URI;
import java.time.Duration;
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
 * @param firewall the Cloud Firewall every VM is created behind; it has no inbound rules, so
 *     nothing on the internet can open a connection to a VM, while the VM can still reach out
 * @param timeout how long one call to Hetzner may take, connecting included
 */
@ConfigurationProperties("workspaced.hetzner")
public record HetznerProperties(
    String token,
    @DefaultValue("https://api.hetzner.cloud/v1") URI baseUrl,
    @DefaultValue("nbg1") String location,
    @DefaultValue("ubuntu-24.04") String image,
    @DefaultValue List<String> sshKeys,
    @DefaultValue("workspaced-ai-vms") String firewall,
    @DefaultValue("10s") Duration timeout) {

  boolean hasToken() {
    return token != null && !token.isBlank();
  }
}
