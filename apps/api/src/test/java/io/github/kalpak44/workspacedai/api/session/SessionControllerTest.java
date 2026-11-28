package io.github.kalpak44.workspacedai.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.recipe.RecipeRequest;
import io.github.kalpak44.workspacedai.api.recipe.RecipeService;
import io.github.kalpak44.workspacedai.api.recipe.Visibility;
import io.github.kalpak44.workspacedai.api.settings.SessionTtlSettings;
import io.github.kalpak44.workspacedai.api.settings.SettingsService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The API against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class SessionControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WorkspaceSessionRepository sessions;
  @Autowired SessionExpiryScheduler cleanup;
  @Autowired SessionExpiryService expiry;
  @Autowired SettingsService settings;
  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transaction;
  @Autowired UserRepository users;
  @Autowired io.github.kalpak44.workspacedai.api.ClusterLocks routingLocks;
  @Autowired RecipeService recipes;
  @Autowired io.github.kalpak44.workspacedai.api.auth.AiUsage usage;
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
    OPENCODE.requests.clear();
    OPENCODE.title = null;
    OPENCODE.messageResponse = null;
    OPENCODE.agent = "build";
    OPENCODE.rejectAgentSwitch = false;
    OPENCODE.rejectAgentList = false;
    OPENCODE.rejectRevert = false;
    OPENCODE.shellCommands.clear();
    OPENCODE.shellExit = 0;
    OPENCODE.whileListingSessions = null;
    OPENCODE.sessionExists = true;
    OPENCODE.sessionUpdatedAt = Instant.now().toEpochMilli();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces
            .create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1", null, "UTC")
            .id();
  }

  @Test
  void formReplyWaitsForTheProviderRoutingLeaseAndThenUsesTheActiveModel() throws Exception {
    var session = create();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var holder =
          executor.submit(
              () ->
                  routingLocks.call(
                      "provider-routing-" + workspace,
                      java.time.Duration.ofMinutes(10),
                      () -> {
                        entered.countDown();
                        try {
                          release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return null;
                      }));
      assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(
                send(
                        "POST",
                        "/" + session.id() + "/forms/frm_age/reply",
                        "{\"answer\":{\"age\":35}}")
                    .statusCode())
            .isEqualTo(409);
        assertThat(OPENCODE.requests)
            .noneMatch(request -> request.path().contains("/form/frm_age/reply"));
      } finally {
        release.countDown();
      }
      holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(
            send("POST", "/" + session.id() + "/forms/frm_age/reply", "{\"answer\":{\"age\":35}}")
                .statusCode())
        .isEqualTo(204);
    var selected = OPENCODE.requests.get(OPENCODE.requests.size() - 2);
    assertThat(selected.path()).endsWith("/model");
    assertThat(selected.body()).contains("\"providerID\":\"openrouter\"");
  }

  @Test
  void expiresByHarnessLastUpdateIncludingModelResponsesAndRechecksBeforeDeleting()
      throws Exception {
    var session = create();
    var policy = new SessionTtlSettings(true, 1);
    settings.setSessionTtl(workspace, policy);
    ageTwoHours(session.id());
    // An old creation time is irrelevant when a model has just responded.
    cleanup.cleanup();
    assertThat(sessions.existsById(session.id())).isTrue();
    OPENCODE.sessionUpdatedAt = Instant.now().minusSeconds(7200).toEpochMilli();
    assertThat(expiry.candidates(workspace)).containsExactly(session.id());
    // A response arriving after the candidate scan rescues the session.
    OPENCODE.sessionUpdatedAt = Instant.now().toEpochMilli();
    assertThat(expiry.expire(session.id())).isFalse();
    OPENCODE.sessionUpdatedAt = Instant.now().minusSeconds(7200).toEpochMilli();
    cleanup.cleanup();
    assertThat(sessions.existsById(session.id())).isFalse();
    assertThat(OPENCODE.shellCommands).contains("rm -rf -- '" + session.path() + "'");
    assertThat(expiry.expire(session.id())).isFalse();
  }

  @Test
  void disablingTtlPreservesPreviouslyExpiredCandidates() throws Exception {
    var session = create();
    OPENCODE.sessionUpdatedAt = Instant.now().minusSeconds(7200).toEpochMilli();
    assertThat(expiry.candidates(workspace)).isEmpty();
    settings.setSessionTtl(workspace, new SessionTtlSettings(true, 1));
    assertThat(expiry.candidates(workspace)).containsExactly(session.id());
    settings.setSessionTtl(workspace, new SessionTtlSettings(false, 1));
    assertThat(expiry.expire(session.id())).isFalse();
    assertThat(sessions.existsById(session.id())).isTrue();
  }

  @Test
  void retriesFailedCleanupWithoutLosingTheSessionIndex() throws Exception {
    var session = create();
    settings.setSessionTtl(workspace, new SessionTtlSettings(true, 1));
    OPENCODE.sessionUpdatedAt = Instant.now().minusSeconds(7200).toEpochMilli();
    OPENCODE.shellExit = 1;
    cleanup.cleanup();
    assertThat(sessions.existsById(session.id())).isTrue();
    // Even though deleting the conversation succeeded, its folder must still be retried.
    // A missing conversation uses the stored creation time; age the row for the retry.
    ageTwoHours(session.id());
    OPENCODE.shellExit = 0;
    cleanup.cleanup();
    assertThat(sessions.existsById(session.id())).isFalse();
  }

  @Test
  void preservesSessionsWhenHarnessUpdateMetadataIsMissing() throws Exception {
    var session = create();
    settings.setSessionTtl(workspace, new SessionTtlSettings(true, 1));
    OPENCODE.sessionUpdatedAt = null;
    cleanup.cleanup();
    assertThat(sessions.existsById(session.id())).isTrue();
  }

  @Test
  void startsASessionInItsOwnDirectoryAndListsIt() throws Exception {
    var created = send("POST", "", null);
    assertThat(created.statusCode()).isEqualTo(201);
    var session = JSON.readValue(created.body(), Session.class);
    // A folder of its own is a project of its own to OpenCode.
    assertThat(session.path()).isEqualTo("/home/workspace/sessions/" + session.id());
    assertThat(OPENCODE.shellCommands).containsExactly("mkdir -p -- '" + session.path() + "'");
    assertThat(session.title()).isNull();
    assertThat(session.createdAt()).isNotNull();
    assertThat(created.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/sessions/" + session.id()));
    assertThat(OPENCODE.requests)
        .anySatisfy(r -> assertThat(r.directory()).isEqualTo(session.path()));
    assertThat(sessions.findById(session.id()))
        .hasValueSatisfying(
            e -> assertThat(e.getHarnessSessionId()).isEqualTo(FakeOpenCode.SESSION));

    var second = JSON.readValue(send("POST", "", null).body(), Session.class);
    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed).hasSize(2);
    // What the create answered is what the list reads back.
    assertThat(listed.get(0).get("createdAt").asString()).isEqualTo(session.createdAt().toString());
    assertThat(listed.get(0).get("id").asString()).isEqualTo(session.id().toString());
    assertThat(listed.get(1).get("id").asString()).isEqualTo(second.id().toString());
  }

  @Test
  void discoversPrimaryAgentsAtTheSessionLocationAndSwitchesWithoutChangingModelOrHistory()
      throws Exception {
    var session = JSON.readValue(send("POST", "", null).body(), Session.class);
    var beforeHistory = send("GET", "/" + session.id() + "/messages", null).body();
    var beforeModel = repository.findById(workspace).orElseThrow().getModel();
    OPENCODE.requests.clear();
    var response = send("GET", "/" + session.id() + "/agent", null);
    assertThat(response.statusCode()).isEqualTo(200);
    var selection = JSON.readValue(response.body(), AgentSelection.class);
    assertThat(selection.current()).isEqualTo("build");
    assertThat(selection.agents())
        .extracting(AgentSelection.Option::id)
        .containsExactly("build", "plan", "writer");
    assertThat(OPENCODE.requests)
        .anySatisfy(
            request -> {
              assertThat(request.path()).isEqualTo("/api/agent");
              assertThat(request.directory()).isEqualTo(session.path());
            });
    var switched = send("PUT", "/" + session.id() + "/agent", "{\"agent\":\"plan\"}");
    assertThat(switched.statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(switched.body(), AgentSelection.class).current()).isEqualTo("plan");
    assertThat(
            JSON.readValue(
                    send("GET", "/" + session.id() + "/agent", null).body(), AgentSelection.class)
                .current())
        .isEqualTo("plan");
    assertThat(repository.findById(workspace).orElseThrow().getModel()).isEqualTo(beforeModel);
    assertThat(send("GET", "/" + session.id() + "/messages", null).body()).isEqualTo(beforeHistory);
    assertThat(OPENCODE.requests)
        .filteredOn(request -> request.method().equals("POST"))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.path())
                  .isEqualTo("/api/session/" + FakeOpenCode.SESSION + "/agent");
              assertThat(request.body()).isEqualTo("{\"agent\":\"plan\"}");
            });
    var requestCount = OPENCODE.requests.size();
    workspace = UUID.randomUUID();
    assertThat(send("GET", "/" + session.id() + "/agent", null).statusCode()).isEqualTo(404);
    assertThat(OPENCODE.requests).hasSize(requestCount);
  }

  @Test
  void rejectsUnknownHiddenAndSubagentOnlySelectionsAndReportsUpstreamFailures() throws Exception {
    var session = JSON.readValue(send("POST", "", null).body(), Session.class);
    OPENCODE.requests.clear();
    for (var agent : new String[] {"explore", "title", "not-installed", " "}) {
      assertThat(
              send(
                      "PUT",
                      "/" + session.id() + "/agent",
                      JSON.writeValueAsString(java.util.Map.of("agent", agent)))
                  .statusCode())
          .isEqualTo(400);
    }
    assertThat(OPENCODE.requests).noneMatch(request -> request.method().equals("POST"));
    OPENCODE.rejectAgentSwitch = true;
    assertThat(send("PUT", "/" + session.id() + "/agent", "{\"agent\":\"plan\"}").statusCode())
        .isEqualTo(503);
    assertThat(OPENCODE.agent).isEqualTo("build");
    OPENCODE.rejectAgentList = true;
    assertThat(send("GET", "/" + session.id() + "/agent", null).statusCode()).isEqualTo(503);
  }

  @Test
  void doesNotGuessAnAgentForASessionWhoseSelectionIsUnset() throws Exception {
    var session = JSON.readValue(send("POST", "", null).body(), Session.class);
    OPENCODE.agent = null;
    assertThat(
            JSON.readValue(
                    send("GET", "/" + session.id() + "/agent", null).body(), AgentSelection.class)
                .current())
        .isNull();
  }

  @Test
  void picksUpATitleTheAgentGaveWhileNoChatWasOpen() throws Exception {
    var session = create();
    assertThat(JSON.readTree(send("GET", "", null).body()).get(0).get("title").isNull()).isTrue();

    OPENCODE.title = "Fix the login form";
    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed.get(0).get("title").asString()).isEqualTo("Fix the login form");
    assertThat(sessions.findById(session.id()))
        .hasValueSatisfying(e -> assertThat(e.getTitle()).isEqualTo("Fix the login form"));
  }

  @Test
  void deletesTheSessionFromTheHarnessWithItsFolder() throws Exception {
    var session = create();
    assertThat(send("DELETE", "/" + session.id(), null).statusCode()).isEqualTo(204);
    assertThat(sessions.findAll()).isEmpty();
    assertThat(OPENCODE.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .contains(Tuple.tuple("DELETE", "/api/session/" + FakeOpenCode.SESSION));
    assertThat(OPENCODE.shellCommands).last().isEqualTo("rm -rf -- '" + session.path() + "'");
    assertThat(send("DELETE", "/" + session.id(), null).statusCode()).isEqualTo(404);
  }

  @Test
  void listsTheSessionsLeftWhenOneIsDeletedMeanwhile() throws Exception {
    var deleted = create();
    var kept = create();
    OPENCODE.sessionExists = false;
    OPENCODE.whileListingSessions = () -> sessions.deleteById(deleted.id());
    var listed = send("GET", "", null);
    assertThat(listed.statusCode()).isEqualTo(200);
    var body = JSON.readTree(listed.body());
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("id").asString()).isEqualTo(kept.id().toString());
    assertThat(body.get(0).get("available").asBoolean()).isFalse();
  }

  @Test
  void keepsTheSharedProjectWhenDeletingASessionFromBeforeFolders() throws Exception {
    var id = UUID.randomUUID();
    sessions.saveAndFlush(
        new WorkspaceSessionEntity(
            id, workspace, TestUsers.ADMIN, Harness.PROJECT_ROOT, FakeOpenCode.SESSION));
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(OPENCODE.shellCommands).isEmpty();
  }

  @Test
  void startsNoSessionWhenItsFolderCannotBeMade() throws Exception {
    OPENCODE.shellExit = 1;
    assertThat(send("POST", "", null).statusCode()).isEqualTo(503);
    assertThat(sessions.findAll()).isEmpty();
    assertThat(OPENCODE.requests)
        .noneMatch(r -> r.method().equals("POST") && r.path().equals("/api/session"));
  }

  @Test
  void attributesCompletedStepsToTheDispatchedPromptThroughTheHttpEntryPoint() throws Exception {
    var session = create();
    assertThat(
            send("POST", "/" + session.id() + "/messages", "{\"text\":\"Usage test\"}")
                .statusCode())
        .isEqualTo(202);
    var input = JSON.readTree(OPENCODE.requests.getLast().body()).path("id").asString();
    assertThat(input).startsWith("msg_");
    OPENCODE.messageResponse =
        """
        {"data":[{"id":"%s","type":"user","time":{"created":1},"text":"Not stored"},
        {"id":"msg_receipt","type":"assistant","time":{"created":2,"completed":3},
        "model":{"providerID":"openrouter","id":"measured-model"},"tokens":{"input":11,"output":7,"reasoning":2,"cache":{"read":3,"write":0}}}],"cursor":{}}
        """
            .formatted(input);
    usage.collect(sessions.findById(session.id()).orElseThrow());
    usage.collect(sessions.findById(session.id()).orElseThrow());
    var totals = usage.totals(repository.findById(workspace).orElseThrow().getOwnerId());
    assertThat(totals)
        .filteredOn(t -> t.workspaceId().equals(workspace))
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.steps()).isEqualTo(1);
              assertThat(t.input()).isEqualByComparingTo("11");
              assertThat(t.model()).isEqualTo("measured-model");
            });
  }

  @Test
  void readsTheConversationAndSendsAndStops() throws Exception {
    var session = create();
    var messages = JSON.readTree(send("GET", "/" + session.id() + "/messages", null).body());
    assertThat(messages).hasSize(2);
    assertThat(messages.get(1).get("parts").get(1).get("tool").asString()).isEqualTo("bash");

    var sent = send("POST", "/" + session.id() + "/messages", "{\"text\":\"Hello\"}");
    assertThat(sent.statusCode()).isEqualTo(202);
    assertThat(OPENCODE.requests.getLast().body()).contains("Hello");
    assertThat(send("POST", "/" + session.id() + "/messages", "{\"text\":\"  \"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "/" + session.id() + "/abort", null).statusCode()).isEqualTo(204);
  }

  @Test
  void editsOnlyUserMessagesAndRegeneratesAfterTruncatingHistory() throws Exception {
    var session = create();
    OPENCODE.requests.clear();
    var path = "/" + session.id() + "/messages/";
    assertThat(send("PUT", path + "msg_u", "{\"text\":\"Revised\"}").statusCode()).isEqualTo(202);
    assertThat(OPENCODE.requests)
        .extracting(FakeOpenCode.Request::path)
        .containsSubsequence(
            "/api/session/" + FakeOpenCode.SESSION + "/model",
            "/api/session/" + FakeOpenCode.SESSION + "/revert/stage",
            "/api/session/" + FakeOpenCode.SESSION + "/revert/commit",
            "/api/session/" + FakeOpenCode.SESSION + "/prompt");
    assertThat(OPENCODE.requests)
        .anySatisfy(
            r -> {
              assertThat(r.path()).endsWith("/revert/stage");
              assertThat(r.body()).contains("\"messageID\":\"msg_u\"", "\"files\":false");
            });
    assertThat(OPENCODE.requests.getLast().body()).contains("Revised");
    OPENCODE.requests.clear();
    assertThat(send("PUT", path + "msg_a", "{\"text\":\"No\"}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", path + "missing", "{\"text\":\"No\"}").statusCode()).isEqualTo(404);
    assertThat(send("PUT", path + "msg_u", "{\"text\":\" \"}").statusCode()).isEqualTo(400);
    assertThat(OPENCODE.requests).noneSatisfy(r -> assertThat(r.path()).contains("revert"));
  }

  @Test
  void doesNotCommitOrPromptWhenTheHarnessRefusesAnEdit() throws Exception {
    var session = create();
    OPENCODE.rejectRevert = true;
    OPENCODE.requests.clear();
    assertThat(
            send("PUT", "/" + session.id() + "/messages/msg_u", "{\"text\":\"Revised\"}")
                .statusCode())
        .isEqualTo(503);
    assertThat(OPENCODE.requests).noneSatisfy(r -> assertThat(r.path()).endsWith("/revert/commit"));
    assertThat(OPENCODE.requests).noneSatisfy(r -> assertThat(r.path()).endsWith("/prompt"));
  }

  @Test
  @Timeout(10)
  void restoresRunningStateOnEverySubscriptionWithoutANewStartEvent() throws Exception {
    var session = create();
    OPENCODE.sessionBusyAt = 10L;
    OPENCODE.holdStream = new java.util.concurrent.CountDownLatch(1);
    try {
      for (var attempt = 0; attempt < 2; attempt++) {
        var request =
            HttpRequest.newBuilder(url("/" + session.id() + "/events"))
                .header("Authorization", TestUsers.bearer(port))
                .header("Accept", "text/event-stream")
                .build();
        var response = http.send(request, BodyHandlers.ofLines());
        try (var lines = response.body()) {
          assertThat(
                  lines
                      .filter(line -> line.startsWith("data:"))
                      .map(line -> line.substring(5).trim())
                      .findFirst())
              .contains("{\"busy\":true}");
        }
      }
    } finally {
      OPENCODE.sessionBusyAt = null;
      OPENCODE.holdStream.countDown();
      OPENCODE.holdStream = null;
    }
  }

  @Test
  @Timeout(10)
  void streamsTheReplyAndKeepsTheTitleTheAgentGaveIt() throws Exception {
    var session = create();
    var request =
        HttpRequest.newBuilder(url("/" + session.id() + "/events"))
            .header("Authorization", TestUsers.bearer(port))
            .header("Accept", "text/event-stream")
            .build();
    var response = http.send(request, BodyHandlers.ofLines());
    assertThat(response.statusCode()).isEqualTo(200);
    var lines = response.body().iterator();
    // The stream opens with a line of its own, before the harness has said anything.
    assertThat(lines.next()).isEqualTo(":connected");
    var names = new ArrayList<String>();
    var data = new ArrayList<String>();
    // The fake harness replays one reply and ends, which ends this stream too.
    lines.forEachRemaining(
        line -> {
          if (line.startsWith("event:")) names.add(line.substring(6));
          if (line.startsWith("data:")) {
            data.add(line.substring(line.startsWith("data: ") ? 6 : 5));
          }
        });
    assertThat(names).contains("message", "part", "delta", "status", "title");
    assertThat(data)
        .contains(
            "{\"messageId\":\"msg_a\","
                + "\"partId\":\"msg_a:text:0\",\"delta\":\"second answer here\"}");
    assertThat(sessions.findById(session.id()))
        .hasValueSatisfying(e -> assertThat(e.getTitle()).isEqualTo("Friendly greeting request"));
  }

  @Test
  void countsARecipeOncePerWorkspaceAndNeverForItsAuthor() throws Exception {
    var ada =
        users
            .findByIdpSubject(TestUsers.subject("ada@example.com"))
            .orElseGet(() -> users.save(TestUsers.newUser("ada@example.com", "Ada", "Lovelace")))
            .getId();
    var hers = recipes.create(ada, "Ada", "Lovelace", recipe(Visibility.PUBLIC)).id();
    var hidden = recipes.create(ada, "Ada", "Lovelace", recipe(Visibility.PRIVATE)).id();
    var mine = recipes.create(TestUsers.ADMIN, "Admin", "", recipe(Visibility.PUBLIC)).id();

    // None of these may stop the session: it has already started when the recipe is counted.
    for (var id : List.of(hers, hers, hidden, mine, UUID.randomUUID())) {
      assertThat(send("POST", "?recipe=" + id, null).statusCode()).isEqualTo(201);
    }

    assertThat(recipes.get(hers, ada).uses()).isEqualTo(1);
    assertThat(recipes.get(hidden, ada).uses()).isZero();
    assertThat(recipes.get(mine, TestUsers.ADMIN).uses()).isZero();
  }

  private static RecipeRequest recipe(Visibility visibility) {
    return new RecipeRequest("WordPress", "", "websites", List.of(), visibility, "1. Install it.");
  }

  @Test
  void answersNotFoundForAnUnknownWorkspaceOrSession() throws Exception {
    assertThat(send("GET", "/" + UUID.randomUUID() + "/messages", null).statusCode())
        .isEqualTo(404);
    workspace = UUID.randomUUID();
    assertThat(send("GET", "", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "", null).statusCode()).isEqualTo(404);
  }

  @Test
  void goesWithItsWorkspace() throws Exception {
    create();
    workspaces.delete(workspace);
    assertThat(sessions.findAll()).isEmpty();
  }

  private Session create() throws Exception {
    return JSON.readValue(send("POST", "", null).body(), Session.class);
  }

  private URI url(String path) {
    return URI.create(
        "http://localhost:" + port + "/api/v1/workspaces/" + workspace + "/sessions" + path);
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(url(path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  /** The database stamps created_at, so aging a session takes an update. */
  private void ageTwoHours(UUID session) {
    transaction.executeWithoutResult(
        _ -> {
          var cb = entityManager.getCriteriaBuilder();
          var update = cb.createCriteriaUpdate(WorkspaceSessionEntity.class);
          var row = update.from(WorkspaceSessionEntity.class);
          update
              .set(
                  row.get(WorkspaceSessionEntity_.createdAt),
                  Instant.now().minus(Duration.ofHours(2)))
              .where(cb.equal(row.get(WorkspaceSessionEntity_.id), session));
          entityManager.createQuery(update).executeUpdate();
        });
  }
}
