package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupervisorPropertiesTest {
  private final UUID canary = UUID.randomUUID();

  private SupervisorProperties config(boolean paused, Set<UUID> ids) {
    return new SupervisorProperties(
        "abc123",
        "https://github.com/owner/repo/releases/download",
        "a".repeat(64),
        "b".repeat(64),
        paused,
        ids);
  }

  @Test
  void pinsBothArchitecturesAndLimitsCanaryRollout() {
    var properties = config(false, Set.of(canary));
    assertThat(properties.forVm(UUID.randomUUID())).isNull();
    var release = properties.forVm(canary);
    assertThat(release.version()).isEqualTo("abc123");
    assertThat(release.artifacts().get("amd64").url())
        .endsWith("/supervisor-abc123/supervisor-linux-amd64");
    assertThat(release.artifacts().get("arm64").sha256()).isEqualTo("b".repeat(64));
    assertThat(config(false, Set.of()).forVm(UUID.randomUUID())).isNotNull();
  }

  @Test
  void pauseAndUnsetVersionLeaveVmAlone() {
    assertThat(config(true, Set.of()).forVm(canary)).isNull();
    assertThat(new SupervisorProperties("", null, null, null, false, null).forVm(canary)).isNull();
  }

  @Test
  void refusesUntrustedOrUnpinnedConfiguration() {
    assertThatThrownBy(
            () ->
                new SupervisorProperties("latest", "http://example.com", "", "", false, null)
                    .forVm(canary))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void excludesSecretsAndMalformedVersionsFromFleetLogs() {
    assertThat(
            new SupervisorReport("old", "new", "rolled_back", null, "candidate health timeout")
                .safe())
        .isTrue();
    assertThat(new SupervisorReport("old", "new", "failed", null, "secret=sk-123").safe())
        .isFalse();
    assertThat(new SupervisorReport("old\nforged", "new", "current", null, null).safe()).isFalse();
  }
}
