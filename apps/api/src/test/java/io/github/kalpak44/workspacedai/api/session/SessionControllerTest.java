package io.github.kalpak44.workspacedai.api.session;

import static io.github.kalpak44.workspacedai.api.session.SseClient.next;
import static io.github.kalpak44.workspacedai.api.session.SseClient.until;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import java.util.ArrayList;
import java.util.Iterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"workspaced.demo.token-delay=0ms", "workspaced.demo.prompt-timeout=5s"})
@Import(TestDatabase.class)
class SessionControllerTest {

  @LocalServerPort int port;

  SseClient client;

  @BeforeEach
  void setUp() {
    client = new SseClient(port);
  }

  @Test
  void streamsARunThatEndsInAnApprovedCommand() throws Exception {
    var id = client.createSession();
    var stream = client.open(id);
    assertThat(stream.statusCode()).isEqualTo(200);
    var lines = stream.body().iterator();

    assertThat(next(lines).orElseThrow().field("state")).isEqualTo("running");
    assertThat(until(lines, "token").field("text")).isEqualTo("I'll ");
    assertThat(until(lines, "command").field("command")).isEqualTo("ls -la");
    assertThat(until(lines, "output").field("text")).isEqualTo("total 8");
    assertThat(until(lines, "exit").data().get("code").asInt()).isZero();

    var question = until(lines, "question");
    var answered =
        client.post(
            "/" + id + "/questions/" + question.field("id") + "/answer",
            "{\"answer\":\"develop\"}");
    assertThat(answered.statusCode()).isEqualTo(202);
    assertThat(until(lines, "answer").field("answer")).isEqualTo("develop");

    var approval = until(lines, "approval");
    assertThat(approval.field("command")).isEqualTo("rm -rf build/");
    var decided =
        client.post(
            "/" + id + "/commands/" + approval.field("id") + "/decision",
            "{\"decision\":\"approve\"}");
    assertThat(decided.statusCode()).isEqualTo(202);

    assertThat(until(lines, "decision").field("decision")).isEqualTo("approve");
    assertThat(until(lines, "command").field("id")).isEqualTo(approval.field("id"));
    assertThat(until(lines, "status").field("state")).isEqualTo("completed");
    assertThat(next(lines)).isEmpty();
  }

  @Test
  void skipsARejectedCommand() throws Exception {
    var id = client.createSession();
    var lines = client.open(id).body().iterator();

    answer(id, lines);
    var approval = until(lines, "approval");
    client.post(
        "/" + id + "/commands/" + approval.field("id") + "/decision", "{\"decision\":\"reject\"}");

    var rest = new ArrayList<String>();
    for (var event = next(lines); event.isPresent(); event = next(lines)) {
      rest.add(event.get().name());
    }
    assertThat(rest)
        .doesNotContain("command")
        .endsWith("message", "status")
        .first()
        .isEqualTo("decision");
  }

  @Test
  void refusesASecondWatcher() throws Exception {
    var id = client.createSession();
    var lines = client.open(id).body().iterator();
    until(lines, "question");

    assertThat(client.open(id).statusCode()).isEqualTo(409);
  }

  @Test
  void answersNotFoundForAnUnknownSession() throws Exception {
    assertThat(client.open("missing").statusCode()).isEqualTo(404);
  }

  @Test
  void answersNotFoundForAPromptNobodyIsWaitingOn() throws Exception {
    var id = client.createSession();
    var response = client.post("/" + id + "/questions/missing/answer", "{\"answer\":\"main\"}");
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsADecisionThatIsNeitherApproveNorReject() throws Exception {
    var id = client.createSession();
    var response = client.post("/" + id + "/commands/any/decision", "{\"decision\":\"maybe\"}");
    assertThat(response.statusCode()).isEqualTo(400);
  }

  private void answer(String id, Iterator<String> lines) throws Exception {
    var question = until(lines, "question");
    client.post(
        "/" + id + "/questions/" + question.field("id") + "/answer", "{\"answer\":\"main\"}");
  }
}
