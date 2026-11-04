package io.github.kalpak44.workspacedai.api.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in contract smoke test against the built harness, without spending provider credits. */
@EnabledIfSystemProperty(named = "opencode.live.url", matches = ".+")
class OpenCodeLiveTest {
  @Test
  void addsAndRemovesGlobalMcpServersThroughOpenCode() throws Exception {
    var harness = new OpenCodeHarness(JsonMapper.builder().build());
    var endpoint =
        new HarnessEndpoint(
            URI.create(System.getProperty("opencode.live.url")),
            System.getProperty("opencode.live.password"));
    var config =
        new Harness.McpServerConfig.Remote(
            "https://example.invalid/mcp", Map.of("Authorization", "Bearer {env:CHECK_TOKEN}"));
    harness.configureMcp(endpoint, Map.of("contract-check", config));
    try {
      // Rebuilt projects list their servers once they have started them.
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!harness.mcpStatus(endpoint).containsKey("contract-check")
          && System.nanoTime() < deadline) {
        Thread.sleep(500);
      }
      assertThat(harness.mcpStatus(endpoint)).containsKey("contract-check");
    } finally {
      harness.removeMcp(endpoint, "contract-check");
    }
    assertThat(harness.mcpStatus(endpoint)).doesNotContainKey("contract-check");
  }

  @Test
  void givesASessionItsOwnProjectThatSeesGlobalMcpServers() {
    var harness = new OpenCodeHarness(JsonMapper.builder().build());
    var endpoint =
        new HarnessEndpoint(
            URI.create(System.getProperty("opencode.live.url")),
            System.getProperty("opencode.live.password"));
    var folder = Harness.SESSIONS_ROOT + "/" + UUID.randomUUID();
    var config =
        new Harness.McpServerConfig.Remote("https://example.invalid/mcp", Map.<String, String>of());
    harness.configureMcp(endpoint, Map.of("folder-check", config));
    harness.createDirectory(endpoint, folder);
    try {
      harness.validate(endpoint, folder);
      var session = harness.createSession(endpoint, folder, "Folder smoke test");
      try {
        assertThat(harness.sessions(endpoint))
            .anyMatch(s -> s.id().equals(session) && folder.equals(s.directory()));
      } finally {
        harness.deleteSession(endpoint, folder, session);
      }
    } finally {
      harness.removeDirectory(endpoint, folder);
      harness.removeMcp(endpoint, "folder-check");
    }
    assertThatThrownBy(() -> harness.validate(endpoint, folder))
        .isInstanceOf(HarnessUnavailableException.class);
  }

  @RepeatedTest(5)
  void createsListsAndDeletesASessionAndRunsABashPty() throws Exception {
    var harness = new OpenCodeHarness(JsonMapper.builder().build());
    var endpoint =
        new HarnessEndpoint(
            URI.create(System.getProperty("opencode.live.url")),
            System.getProperty("opencode.live.password"));
    harness.validate(endpoint, Harness.PROJECT_ROOT);
    var heartbeat = new CountDownLatch(1);
    try (var stream = harness.stream(endpoint, event -> heartbeat.countDown(), () -> {})) {
      assertThat(heartbeat.await(5, TimeUnit.SECONDS)).isTrue();
    }
    var session = harness.createSession(endpoint, Harness.PROJECT_ROOT, "Contract smoke test");
    try {
      assertThat(harness.sessions(endpoint)).anyMatch(s -> s.id().equals(session));
      assertThat(harness.messages(endpoint, Harness.PROJECT_ROOT, session)).isEmpty();
      var output = new ByteArrayOutputStream();
      try (var terminal = harness.terminal(endpoint, Harness.PROJECT_ROOT, output, () -> {})) {
        terminal.resize(100, 30);
        terminal.input("printf 'LIVE_%s\\n' 'PTY_OK'\n".getBytes(StandardCharsets.UTF_8));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!output.toString(StandardCharsets.UTF_8).contains("LIVE_PTY_OK")
            && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("LIVE_PTY_OK");
      }
    } finally {
      harness.deleteSession(endpoint, Harness.PROJECT_ROOT, session);
    }
  }
}
