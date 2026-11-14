package io.github.kalpak44.workspacedai.vmmanager.vm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param image the harness container every VM runs, as an image reference; blank leaves the app
 *     running, and a VM asking for its harness answers 503
 */
@ConfigurationProperties("workspaced.harness")
public record HarnessProperties(String image) {

  boolean isConfigured() {
    return image != null && !image.isBlank();
  }
}
