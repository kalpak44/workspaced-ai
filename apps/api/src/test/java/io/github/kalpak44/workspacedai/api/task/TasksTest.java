package io.github.kalpak44.workspacedai.api.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class TasksTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String DIRECTORY = Harness.TASKS_DIRECTORY + "/";
  private static final String PR_SUMMARY =
      "---\ncron: 0 9 * * 1-5\ntimezone: Europe/Sofia\n---\nSummarise the open PRs.";

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WorkspaceSessionRepository sessions;
  @Autowired TaskRunner runner;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID workspace;

  private static FakeOpenCode start() {
    try {
      return new FakeOpenCode();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void harness(DynamicPropertyRegistry registry) {
    registry.add("workspaced.workspace.local-harness.url", () -> OPENCODE.endpoint().baseUrl());
    registry.add("workspaced.workspace.local-harness.password", () -> FakeOpenCode.PASSWORD);
  }

  @AfterAll
  static void stop() {
    OPENCODE.close();
  }

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    OPENCODE.files.clear();
    OPENCODE.requests.clear();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces
            .create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1", null)
            .id();
  }

  @Test
  void listsTheWorkspacesTasksAsTheAgentWroteThem() throws Exception {
    OPENCODE.files.put(DIRECTORY + "pr-summary.md", PR_SUMMARY);
    // Quoted values, as the agent may write them, and a file with no schedule at all.
    OPENCODE.files.put(
        DIRECTORY + "friday.md", "---\ncron: \"30 17 * * FRI\"\ntimezone: 'UTC'\n---\nWrap up.");
    OPENCODE.files.put(DIRECTORY + "broken.md", "Just a prompt.");

    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed).hasSize(3);
    assertThat(listed.get(0).get("name").asString()).isEqualTo("broken");
    assertThat(listed.get(0).get("cron").asString()).isEmpty();
    assertThat(listed.get(0).get("prompt").asString()).isEqualTo("Just a prompt.");
    assertThat(listed.get(1).get("cron").asString()).isEqualTo("30 17 * * FRI");
    assertThat(listed.get(1).get("timeZone").asString()).isEqualTo("UTC");
    assertThat(listed.get(2).get("name").asString()).isEqualTo("pr-summary");
    assertThat(listed.get(2).get("prompt").asString()).isEqualTo("Summarise the open PRs.");
  }

  @Test
  void createsReplacesAndDeletesTaskFiles() throws Exception {
    var body =
        "{\"cron\":\"0 9 * * 1-5\",\"timeZone\":\"Europe/Sofia\",\"prompt\":\"Summarise the open PRs.\"}";
    assertThat(send("PUT", "/pr-summary", body).statusCode()).isEqualTo(200);
    assertThat(OPENCODE.files).containsEntry(DIRECTORY + "pr-summary.md", PR_SUMMARY);

    send("PUT", "/pr-summary", body.replace("0 9", "0 10"));
    assertThat(OPENCODE.files.get(DIRECTORY + "pr-summary.md")).contains("cron: 0 10 * * 1-5");

    assertThat(send("DELETE", "/pr-summary", null).statusCode()).isEqualTo(204);
    assertThat(OPENCODE.files).doesNotContainKey(DIRECTORY + "pr-summary.md");
    assertThat(send("DELETE", "/pr-summary", null).statusCode()).isEqualTo(404);
  }

  @Test
  void refusesBadNamesSchedulesAndTimeZones() throws Exception {
    var ok = "{\"cron\":\"0 9 * * *\",\"timeZone\":\"UTC\",\"prompt\":\"x\"}";
    for (var name : new String[] {"..%2Fsecrets", "-rf", "a.b", "x".repeat(64)}) {
      assertThat(send("PUT", "/" + name, ok).statusCode()).as(name).isEqualTo(400);
    }
    for (var cron : new String[] {"0 25 * * *", "0 9 * *", "0 0 9 * * *", "nonsense"}) {
      assertThat(send("PUT", "/ok", ok.replace("0 9 * * *", cron)).statusCode())
          .as(cron)
          .isEqualTo(400);
    }
    assertThat(send("PUT", "/ok", ok.replace("UTC", "Mars/Olympus")).statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/ok", "{}").statusCode()).isEqualTo(400);
    assertThat(OPENCODE.files.keySet()).allMatch(f -> !f.endsWith("ok.md"));
  }

  @Test
  void answersNotFoundForSomeoneElsesOrAnUnknownWorkspace() throws Exception {
    workspace = UUID.randomUUID();
    assertThat(send("GET", "", null).statusCode()).isEqualTo(404);
  }

  @Test
  void runsADueTaskInANewSessionNamedForIt() {
    OPENCODE.files.put(DIRECTORY + "pr-summary.md", PR_SUMMARY);
    // Not due, without a valid schedule, or without a prompt: none of these runs.
    OPENCODE.files.put(DIRECTORY + "later.md", PR_SUMMARY.replace("0 9", "0 10"));
    OPENCODE.files.put(DIRECTORY + "broken.md", "Just a prompt.");
    OPENCODE.files.put(DIRECTORY + "empty.md", PR_SUMMARY.replace("Summarise the open PRs.", ""));

    // Monday 10 November 2026, 09:00 in Sofia.
    runner.runDue(workspace, Instant.parse("2026-11-10T07:00:00Z"));

    assertThat(sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspace)).hasSize(1);
    assertThat(OPENCODE.requests)
        .filteredOn(r -> r.method().equals("POST") && r.path().equals("/api/session"))
        .singleElement()
        .satisfies(r -> assertThat(r.body()).contains("\"title\":\"pr-summary\""));
    assertThat(OPENCODE.requests)
        .filteredOn(r -> r.path().endsWith("/prompt"))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.body())
                    .contains("scheduled task \\\"pr-summary\\\"", "Summarise the open PRs."));
  }

  @Test
  void runsNothingOutsideItsSchedule() {
    OPENCODE.files.put(DIRECTORY + "pr-summary.md", PR_SUMMARY);
    // 09:00 UTC is 11:00 in Sofia, and Sunday 09:00 there is not a weekday.
    runner.runDue(workspace, Instant.parse("2026-11-10T09:00:00Z"));
    runner.runDue(workspace, Instant.parse("2026-11-08T07:00:00Z"));
    assertThat(sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspace)).isEmpty();
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/workspaces/"
                        + workspace
                        + "/tasks"
                        + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
