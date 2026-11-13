package io.github.kalpak44.workspacedai.api.runtime;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param domain each workspace's web server is public as box-<name> under it; blank turns the web
 *     server off, as it is locally. The API only names Services after these names; routing the
 *     domain to them is the cluster's job.
 * @param reserved names no workspace may take: "off", which the agent's request uses to turn the
 *     web server off. The box- prefix already keeps every name clear of the domain's own hosts.
 */
@ConfigurationProperties("workspaced.web-server")
public record WebServerProperties(
    @DefaultValue("") String domain, @DefaultValue("off") List<String> reserved) {

  public boolean enabled() {
    return !domain.isBlank();
  }
}
