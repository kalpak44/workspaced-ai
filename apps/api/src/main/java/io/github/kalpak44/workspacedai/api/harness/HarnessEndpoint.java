package io.github.kalpak44.workspacedai.api.harness;

import java.net.URI;
import java.nio.file.Path;

/**
 * Where a workspace's harness answers, and the password its server checks.
 *
 * @param baseUrl the opencode server, e.g. {@code http://10.42.0.17:4096}
 * @param password checked with HTTP basic auth; the user name is opencode's default
 * @param callerToken the service account token file sent to the agent hub that carries the call,
 *     read afresh each time since the kubelet rotates it; null when no hub is in between
 */
public record HarnessEndpoint(URI baseUrl, String password, Path callerToken) {

  public HarnessEndpoint(URI baseUrl, String password) {
    this(baseUrl, password, null);
  }

  @Override
  public String toString() {
    // Never the password: an endpoint ends up in log lines and exception messages.
    return "HarnessEndpoint[" + baseUrl + "]";
  }
}
