package io.github.kalpak44.workspacedai.api.runtime;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param image the harness each workspace pod runs; the deployment pins it to a tag
 * @param stopTimeout how long a delete waits for the pod to be gone before it gives up
 * @param storageSize persistent home size requested for each Kubernetes workspace
 * @param storageClass storage class for workspace homes; blank uses the cluster default
 * @param localHarness outside Kubernetes, a harness to chat with, e.g. one started with docker
 */
@ConfigurationProperties("workspaced.workspace")
public record WorkspaceProperties(
    @DefaultValue("ghcr.io/kalpak44/workspaced-ai-harness:latest") String image,
    @DefaultValue("60s") Duration stopTimeout,
    @DefaultValue("10Gi") String storageSize,
    @DefaultValue("") String storageClass,
    @DefaultValue LocalHarness localHarness) {

  /**
   * @param url unset means local workspaces have no harness, and chatting answers 503
   * @param password what that harness was started with in OPENCODE_SERVER_PASSWORD
   */
  public record LocalHarness(URI url, String password) {}
}
