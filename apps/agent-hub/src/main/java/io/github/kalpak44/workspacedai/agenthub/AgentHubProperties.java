package io.github.kalpak44.workspacedai.agenthub;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param vmManagerUrl the VM manager's base URL, context path included, which ready and heartbeat
 *     reports are passed on to
 * @param enrollmentTtl how long an enrollment token stays usable after it is issued
 * @param signatureSkew how far a signed request's timestamp may be from this app's clock, either
 *     way, before it is refused as a possible replay
 * @param vmManagerTimeout how long one call to the VM manager may take, connecting included
 */
@ConfigurationProperties("workspaced.agent-hub")
public record AgentHubProperties(
    @DefaultValue("http://workspaced-ai-vm-manager:8080/api/v1") URI vmManagerUrl,
    @DefaultValue("30m") Duration enrollmentTtl,
    @DefaultValue("60s") Duration signatureSkew,
    @DefaultValue("10s") Duration vmManagerTimeout) {}
