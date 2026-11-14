package io.github.kalpak44.workspacedai.api.runtime;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url the agent hub's API root, e.g. {@code http://workspaced-ai-agent-hub:8080/api/v1},
 *     whose tunnel reaches each VM's harness; unset, as it is locally, no harness is reachable
 */
@ConfigurationProperties("workspaced.agent-hub")
public record AgentHubProperties(URI url) {

  boolean isConfigured() {
    return url != null && !url.toString().isBlank();
  }
}
