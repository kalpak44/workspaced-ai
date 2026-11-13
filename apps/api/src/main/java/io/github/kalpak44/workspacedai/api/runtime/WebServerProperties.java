package io.github.kalpak44.workspacedai.api.runtime;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param domain each workspace's web server is public as a name under it; blank turns the web
 *     server off, as it is locally. The API only names Services after these names; routing the
 *     domain to them is the cluster's job.
 * @param reserved names no workspace may take, because the domain already serves them, or, for
 *     "off", because the agent's request uses it to turn the web server off
 */
@ConfigurationProperties("workspaced.web-server")
public record WebServerProperties(
    @DefaultValue("") String domain,
    @DefaultValue({"admin", "ai", "api", "app", "auth", "docs", "mail", "off", "status", "www"})
        List<String> reserved) {

  public boolean enabled() {
    return !domain.isBlank();
  }
}
