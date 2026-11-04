package io.github.kalpak44.workspacedai.api.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in contract smoke test against the built harness, without spending provider credits. */
@EnabledIfSystemProperty(named = "opencode.live.url", matches = ".+")
class OpenCodeLiveTest {
  @org.junit.jupiter.api.RepeatedTest(5)
  void createsListsAndDeletesASessionAndRunsABashPty() throws Exception {
    var harness = new OpenCodeHarness(JsonMapper.builder().build());
    var endpoint =
        new HarnessEndpoint(
            URI.create(System.getProperty("opencode.live.url")),
            System.getProperty("opencode.live.password"));
    harness.validate(endpoint, Harness.PROJECT_ROOT);
    var heartbeat = new java.util.concurrent.CountDownLatch(1);
    try (var stream = harness.stream(endpoint, event -> heartbeat.countDown(), () -> {})) {
      assertThat(heartbeat.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    var session = harness.createSession(endpoint, Harness.PROJECT_ROOT, "Contract smoke test");
    try {
      assertThat(harness.sessions(endpoint)).anyMatch(s -> s.id().equals(session));
      assertThat(harness.messages(endpoint, Harness.PROJECT_ROOT, session)).isEmpty();
      var output = new ByteArrayOutputStream();
      try (var terminal = harness.terminal(endpoint, Harness.PROJECT_ROOT, output, () -> {})) {
        terminal.resize(100, 30);
        terminal.input("printf 'LIVE_%s\\n' 'PTY_OK'\n".getBytes(StandardCharsets.UTF_8));
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
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
