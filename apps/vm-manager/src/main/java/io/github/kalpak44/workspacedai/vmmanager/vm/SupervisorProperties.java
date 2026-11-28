package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independent release pin; checksums are operator-approved trust anchors, not downloaded metadata.
 */
@ConfigurationProperties("workspaced.supervisor")
public record SupervisorProperties(
    String version,
    String baseUrl,
    String amd64Sha256,
    String arm64Sha256,
    boolean paused,
    Set<UUID> vmIds) {
  public record Artifact(String url, String sha256) {}

  public record Release(String version, Map<String, Artifact> artifacts) {}

  public Release forVm(UUID id) {
    if (paused
        || version == null
        || version.isBlank()
        || (vmIds != null && !vmIds.isEmpty() && !vmIds.contains(id))) return null;
    if (!version.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        || baseUrl == null
        || !baseUrl.startsWith("https://")
        || amd64Sha256 == null
        || !amd64Sha256.matches("[a-f0-9]{64}")
        || arm64Sha256 == null
        || !arm64Sha256.matches("[a-f0-9]{64}")) {
      throw new IllegalStateException("Invalid supervisor release pin");
    }
    var base = baseUrl.replaceAll("/+$", "") + "/supervisor-" + version + "/supervisor-linux-";
    return new Release(
        version,
        Map.of(
            "amd64", new Artifact(base + "amd64", amd64Sha256),
            "arm64", new Artifact(base + "arm64", arm64Sha256)));
  }
}
