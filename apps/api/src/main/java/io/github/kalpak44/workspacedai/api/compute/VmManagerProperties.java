package io.github.kalpak44.workspacedai.api.compute;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url the VM manager's API root, e.g. {@code http://workspaced-ai-vm-manager:8080/api/v1};
 *     unset, no workspace can be created or listed, and /vm-sizes answers 503
 * @param timeout how long one call may take; a create waits on Hetzner accepting the server
 */
@ConfigurationProperties("workspaced.vm-manager")
public record VmManagerProperties(URI url, @DefaultValue("30s") Duration timeout) {

  boolean isConfigured() {
    return url != null && !url.toString().isBlank();
  }
}
