package io.github.kalpak44.workspacedai.api.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenCodeHarnessTest {

  static final String DIRECTORY = "/home/workspace/sessions/cccc";

  FakeOpenCode opencode;
  final OpenCodeHarness harness = new OpenCodeHarness(JsonMapper.builder().build());

  @BeforeEach
  void setUp() throws Exception {
    opencode = new FakeOpenCode();
  }

  @AfterEach
  void tearDown() {
    opencode.close();
  }

  @Test
  void createsASessionInItsDirectoryWithThePassword() {
    var id = harness.createSession(opencode.endpoint(), DIRECTORY, null);
    assertThat(id).isEqualTo(FakeOpenCode.SESSION);
    var request = opencode.requests.getFirst();
    assertThat(request.directory()).isEqualTo(DIRECTORY);
    assertThat(request.body()).isEqualTo("{}");
  }

  @Test
  void deletesASessionAndToleratesOneAlreadyGone() {
    harness.deleteSession(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION);
    harness.deleteSession(opencode.endpoint(), DIRECTORY, "ses_gone");
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("DELETE", "/session/" + FakeOpenCode.SESSION),
            org.assertj.core.groups.Tuple.tuple("DELETE", "/session/ses_gone"));
  }

  @Test
  void readsTheConversationKeepingOnlyWhatAChatShows() {
    var messages = harness.messages(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION);
    assertThat(messages)
        .containsExactly(
            new ChatMessage(
                "msg_u",
                "user",
                List.of(new ChatPart("prt_u", "text", "List the files", null, null))),
            new ChatMessage(
                "msg_a",
                "assistant",
                List.of(
                    new ChatPart("prt_r", "reasoning", "I should run ls.", null, null),
                    new ChatPart("prt_t", "tool", "List files", "bash", "completed"),
                    new ChatPart("prt_x", "text", "There are none.", null, null))));
  }

  @Test
  void readsTheTitleOnceItIsNoLongerThePlaceholder() {
    assertThat(harness.title(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION)).isEmpty();
    opencode.title = "Fix the login form";
    assertThat(harness.title(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION))
        .hasValue("Fix the login form");
  }

  @Test
  void promptsWithoutWaitingAndAborts() {
    harness.prompt(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION, "Hi \"there\"");
    harness.abort(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION);
    assertThat(opencode.requests.get(0).path()).endsWith("/prompt_async");
    var json = JsonMapper.builder().build();
    assertThat(json.readTree(opencode.requests.get(0).body()))
        .isEqualTo(
            json.readTree("{\"parts\":[{\"type\":\"text\",\"text\":\"Hi \\\"there\\\"\"}]}"));
    assertThat(opencode.requests.get(1).path()).endsWith("/abort");
  }

  @Test
  void reportsAHarnessThatRefusesFailsOrIsNotThere() {
    assertThatThrownBy(() -> harness.createSession(opencode.wrongPassword(), DIRECTORY, null))
        .isInstanceOf(HarnessUnavailableException.class);
    assertThatThrownBy(() -> harness.messages(opencode.endpoint(), DIRECTORY, "broken"))
        .isInstanceOf(HarnessUnavailableException.class)
        .hasMessageContaining("500");
    var nowhere = new HarnessEndpoint(URI.create("http://localhost:1"), "pw");
    assertThatThrownBy(() -> harness.prompt(nowhere, DIRECTORY, "s", "hi"))
        .isInstanceOf(HarnessUnavailableException.class);
  }

  @Test
  void streamsOneSessionsReplyAsOpencodeSendsIt() throws Exception {
    var events = new CopyOnWriteArrayList<HarnessEvent>();
    var ended = new CountDownLatch(1);
    try (var _ =
        harness.stream(
            opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION, events::add, ended::countDown)) {
      assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(opencode.requests.getFirst().directory()).isEqualTo(DIRECTORY);

    assertThat(events).contains(new HarnessEvent.Status(true), new HarnessEvent.Status(false));
    assertThat(events).contains(new HarnessEvent.Title("Friendly greeting request"));
    assertThat(events)
        .filteredOn(HarnessEvent.Delta.class::isInstance)
        .extracting(e -> ((HarnessEvent.Delta) e).delta())
        .containsExactly("second answer here");
    // The last snapshot of the text part holds the whole reply, so a missed delta heals.
    assertThat(events)
        .filteredOn(HarnessEvent.Part.class::isInstance)
        .extracting(e -> ((HarnessEvent.Part) e).part())
        .filteredOn(p -> p.type().equals("text"))
        .last()
        .extracting(ChatPart::text)
        .isEqualTo("second answer here");
    assertThat(events)
        .anyMatch(e -> e instanceof HarnessEvent.Message m && "assistant".equals(m.role()));
    assertThat(events).anyMatch(HarnessEvent.Heartbeat.class::isInstance);
    assertThat(events).noneMatch(HarnessEvent.Error.class::isInstance);
  }

  @Test
  void ignoresOtherSessionsAndPlaceholderTitles() throws Exception {
    var events = new CopyOnWriteArrayList<HarnessEvent>();
    var ended = new CountDownLatch(1);
    try (var _ =
        harness.stream(
            opencode.endpoint(), DIRECTORY, "ses_other", events::add, ended::countDown)) {
      assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(events).allMatch(HarnessEvent.Heartbeat.class::isInstance);

    var json = JsonMapper.builder().build();
    var placeholder =
        json.readTree(
            "{\"type\":\"session.updated\",\"properties\":{\"sessionID\":\"s\",\"info\":"
                + "{\"title\":\"New session - 2026-11-03T15:09:46.948Z\"}}}");
    assertThat(OpenCodeHarness.event(placeholder, "s")).isNull();
    var error =
        json.readTree(
            "{\"type\":\"session.error\",\"properties\":{\"sessionID\":\"s\",\"error\":"
                + "{\"name\":\"APIError\",\"data\":{\"message\":\"User not found.\"}}}}");
    assertThat(OpenCodeHarness.event(error, "s"))
        .isEqualTo(new HarnessEvent.Error("User not found."));
    var nameless =
        json.readTree(
            "{\"type\":\"session.error\",\"properties\":{\"sessionID\":\"s\",\"error\":{}}}");
    assertThat(OpenCodeHarness.event(nameless, "s"))
        .isEqualTo(new HarnessEvent.Error("The reply failed"));
  }

  @Test
  void stopsReadingOnceClosedWithoutReportingAnEnd() throws Exception {
    opencode.holdStream = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<HarnessEvent>();
    var ended = new CountDownLatch(1);
    var stream =
        harness.stream(
            opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION, events::add, ended::countDown);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (events.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(events).containsExactly(new HarnessEvent.Heartbeat());
    stream.close();
    stream.close();
    assertThat(ended.await(500, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  void neverPrintsThePassword() {
    assertThat(opencode.endpoint().toString()).doesNotContain(FakeOpenCode.PASSWORD + "]");
    assertThat(new HarnessEndpoint(URI.create("http://h:1"), "secret").toString())
        .isEqualTo("HarnessEndpoint[http://h:1]");
  }
}
