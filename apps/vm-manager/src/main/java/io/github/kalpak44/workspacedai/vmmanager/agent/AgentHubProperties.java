package io.github.kalpak44.workspacedai.vmmanager.agent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url the agent hub's API root inside the cluster, where enrollment tokens are issued and
 *     agents revoked; unset, VMs are created with no agent identity at all
 * @param publicUrl the agent hub's API root as a VM reaches it from the internet
 * @param timeout how long one call to the agent hub may take, connecting included
 * @param tokenFile the projected service account token this pod proves itself to the agent hub
 *     with; the kubelet rotates it, so it is read afresh for every call
 */
@ConfigurationProperties("workspaced.agent-hub")
public record AgentHubProperties(
    URI url,
    @DefaultValue("https://agent.workspaced.online/api/v1") URI publicUrl,
    @DefaultValue("10s") Duration timeout,
    @DefaultValue("/var/run/secrets/workspaced-ai/agent-hub/token") Path tokenFile) {

  boolean isConfigured() {
    return url != null && !url.toString().isBlank();
  }
}
