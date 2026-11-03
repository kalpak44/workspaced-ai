package io.github.kalpak44.workspacedai.api.harness;

import java.net.URI;

/**
 * Where a workspace's harness answers, and the password its server checks.
 *
 * @param baseUrl the opencode server, e.g. {@code http://10.42.0.17:4096}
 * @param password checked with HTTP basic auth; the user name is opencode's default
 */
public record HarnessEndpoint(URI baseUrl, String password) {

  @Override
  public String toString() {
    // Never the password: an endpoint ends up in log lines and exception messages.
    return "HarnessEndpoint[" + baseUrl + "]";
  }
}
