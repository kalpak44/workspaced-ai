package io.github.kalpak44.workspacedai.api.instructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatusChanged;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class InstructionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String GUIDE = Harness.INSTRUCTIONS_DIRECTORY + "/guide.md";
  private static final String GENERAL = Harness.INSTRUCTIONS_DIRECTORY + "/general.md";
  private static final String OWN = Harness.INSTRUCTIONS_DIRECTORY + "/workspace.md";

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired UserRepository users;
  @Autowired ApplicationEventPublisher events;
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
    var admin = users.findById(TestUsers.ADMIN).orElseThrow();
    admin.setInstructions("");
    users.save(admin);
    OPENCODE.files.clear();
    OPENCODE.files.put(GUIDE, "# How this workspace works");
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void writesAWorkspacesInstructionsWhereItsAgentReadsThem() throws Exception {
    var empty = JSON.readTree(send("GET", workspacePath(), null).body());
    assertThat(empty.get("instructions").asString()).isEmpty();
    assertThat(empty.get("guide").asString()).isEqualTo("# How this workspace works");

    var reloads = OPENCODE.reloads;
    var saved = send("PUT", workspacePath(), "{\"instructions\":\"Use pnpm.\"}");
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(saved.body()).get("applied").asBoolean()).isTrue();
    assertThat(OPENCODE.files).containsEntry(OWN, "Use pnpm.");
    assertThat(OPENCODE.reloads).isEqualTo(reloads + 1);
    assertThat(
            JSON.readTree(send("GET", workspacePath(), null).body()).get("instructions").asString())
        .isEqualTo("Use pnpm.");

    // Saving what the workspace already has leaves its agent alone.
    send("PUT", workspacePath(), "{\"instructions\":\"Use pnpm.\"}");
    assertThat(OPENCODE.reloads).isEqualTo(reloads + 1);
  }

  @Test
  void givesTheGeneralInstructionsToEveryWorkspaceOfTheUser() throws Exception {
    assertThat(
            JSON.readTree(send("GET", "/account/instructions", null).body())
                .get("instructions")
                .asString())
        .isEmpty();
    var saved = send("PUT", "/account/instructions", "{\"instructions\":\"Answer briefly.\"}");
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(
            JSON.readTree(send("GET", "/account/instructions", null).body())
                .get("instructions")
                .asString())
        .isEqualTo("Answer briefly.");
    awaitFile(GENERAL, "Answer briefly.");
  }

  @Test
  void givesAWorkspaceThatBecomesReadyWhatItMissed() throws Exception {
    var admin = users.findById(TestUsers.ADMIN).orElseThrow();
    admin.setInstructions("Answer briefly.");
    users.save(admin);
    var entity = repository.findById(workspace).orElseThrow();
    entity.setInstructions("Use pnpm.");
    repository.save(entity);
    // As a replaced pod, whose home was written before: only the workspace's own changed.
    OPENCODE.files.put(GENERAL, "Answer briefly.");

    events.publishEvent(new WorkspaceStatusChanged(workspace, WorkspaceStatus.READY));
    awaitFile(OWN, "Use pnpm.");
    assertThat(OPENCODE.files).containsEntry(GENERAL, "Answer briefly.");
  }

  @Test
  void refusesInstructionsTooLongOrMissing() throws Exception {
    var tooLong = "{\"instructions\":\"" + "x".repeat(100_001) + "\"}";
    assertThat(send("PUT", workspacePath(), tooLong).statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/account/instructions", "{}").statusCode()).isEqualTo(400);
    assertThat(send("GET", "/workspaces/" + UUID.randomUUID() + "/instructions", null).statusCode())
        .isEqualTo(404);
  }

  private String workspacePath() {
    return "/workspaces/" + workspace + "/instructions";
  }

  /** The general instructions are written in the background, so they are waited for. */
  private static void awaitFile(String path, String expected) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!expected.equals(OPENCODE.files.get(path))) {
      assertThat(System.nanoTime()).as("%s holds %s", path, expected).isLessThan(deadline);
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    }
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
