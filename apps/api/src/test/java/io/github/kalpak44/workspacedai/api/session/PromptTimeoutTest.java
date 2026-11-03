package io.github.kalpak44.workspacedai.api.session;

import static io.github.kalpak44.workspacedai.api.session.SseClient.next;
import static io.github.kalpak44.workspacedai.api.session.SseClient.until;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"workspaced.demo.token-delay=0ms", "workspaced.demo.prompt-timeout=100ms"})
class PromptTimeoutTest {

  @LocalServerPort int port;

  @Test
  void closesTheStreamWhenAQuestionGoesUnanswered() throws Exception {
    var client = new SseClient(port);
    var id = client.createSession();
    var lines = client.open(id).body().iterator();

    until(lines, "question");

    assertThat(next(lines)).isEmpty();
    assertThat(client.open(id).statusCode()).isEqualTo(404);
  }
}
