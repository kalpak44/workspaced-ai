package io.github.kalpak44.workspacedai.api.runtime;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url the agent hub's API root, e.g. {@code http://workspaced-ai-agent-hub:8080/api/v1},
 *     whose tunnel reaches each VM's harness; unset, as it is locally, no harness is reachable
 * @param tokenFile the projected service account token the hub accepts from this API, on the
 *     harness routes alone
 */
@ConfigurationProperties("workspaced.agent-hub")
public record AgentHubProperties(
    URI url, @DefaultValue("/var/run/secrets/workspaced-ai/agent-hub/token") Path tokenFile) {

  boolean isConfigured() {
    return url != null && !url.toString().isBlank();
  }
}
