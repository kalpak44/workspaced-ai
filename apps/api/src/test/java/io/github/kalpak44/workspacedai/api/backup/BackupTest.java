package io.github.kalpak44.workspacedai.api.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionEntity;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.OptionalLong;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, with the storage stubbed. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class BackupTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String STATUS = Harness.BACKUP_DIRECTORY + "/status.json";

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WorkspaceSessionRepository sessions;
  @Autowired BackupRepository backups;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean BackupStorage storage;

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
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("workspaced.workspace.local-harness.url", () -> OPENCODE.endpoint().baseUrl());
    registry.add("workspaced.workspace.local-harness.password", () -> FakeOpenCode.PASSWORD);
    registry.add("workspaced.backup.poll-interval", () -> "50ms");
    registry.add("workspaced.backup.stall-timeout", () -> "2s");
  }

  @AfterAll
  static void stop() {
    OPENCODE.close();
  }

  @BeforeEach
  void setUp() {
    backups.deleteAll();
    repository.deleteAll();
    OPENCODE.files.clear();
    OPENCODE.shellCommands.clear();
    OPENCODE.shellExit = 0;
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    when(storage.uploadUrl(anyString(), any())).thenReturn(URI.create("https://s3/up?sig=1"));
    when(storage.downloadUrl(anyString(), any())).thenReturn(URI.create("https://s3/down?sig=2"));
    when(storage.size(anyString())).thenReturn(OptionalLong.of(1234));
    workspace =
        workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void recordsTheSettingsAndHasTheWorkspaceUploadItsHome() throws Exception {
    OPENCODE.files.put(Harness.INSTRUCTIONS_DIRECTORY + "/chat.md", "# chat");
    OPENCODE.files.put(Harness.TASKS_DIRECTORY + "/daily.md", "---\ncron: 0 9 * * *\n---\nhi");
    OPENCODE.files.put(Harness.VARIABLES_FILE, "PORT=8080\n");
    sessions.save(new WorkspaceSessionEntity(UUID.randomUUID(), workspace, "/p", "ses_1"));

    var started = send("POST", "/workspaces/" + workspace + "/backups", null);
    assertThat(started.statusCode()).isEqualTo(202);
    var backup = JSON.readTree(started.body());
    assertThat(backup.get("status").asString()).isEqualTo("RUNNING");
    assertThat(backup.get("workspaceName").asString()).isEqualTo("Research");
    assertThat(backup.get("instructions").get(0).asString()).isEqualTo("chat");
    assertThat(backup.get("tasks").get(0).asString()).isEqualTo("daily");
    assertThat(backup.get("variables").get(0).asString()).isEqualTo("PORT");
    assertThat(backup.get("sessions").asInt()).isEqualTo(1);
    var id = backup.get("id").asString();
    var job = JSON.readTree(OPENCODE.files.get(Harness.BACKUP_DIRECTORY + "/job.json"));
    assertThat(job.get("id").asString()).isEqualTo(id);
    assertThat(job.get("uploadUrl").asString()).isEqualTo("https://s3/up?sig=1");
    assertThat(OPENCODE.shellCommands).anyMatch(c -> c.startsWith("setsid workspace-backup run"));

    report(id, "archiving", 50, 100);
    await().until(() -> backup(id).get("progress").asInt() == 40);
    report(id, "uploading", 1, 2);
    await().until(() -> backup(id).get("phase").asString().equals("uploading"));
    report(id, "done", 1234, 1234);
    await().until(() -> backup(id).get("status").asString().equals("COMPLETED"));
    var completed = backup(id);
    assertThat(completed.get("progress").asInt()).isEqualTo(100);
    assertThat(completed.get("sizeBytes").asLong()).isEqualTo(1234);
    assertThat(completed.get("completedAt").isNull()).isFalse();
  }

  @Test
  void failsWhatTheWorkspaceReportsFailed() throws Exception {
    var id = startBackup();
    OPENCODE.files.put(STATUS, "{\"id\":\"" + id + "\",\"phase\":\"failed\",\"error\":\"disk\"}");
    await().until(() -> backup(id).get("status").asString().equals("FAILED"));
    assertThat(backup(id).get("error").asString()).isEqualTo("disk");
  }

  @Test
  void failsAnUploadThatDidNotArrive() throws Exception {
    when(storage.size(anyString())).thenReturn(OptionalLong.empty());
    var id = startBackup();
    report(id, "done", 1, 1);
    await().until(() -> backup(id).get("status").asString().equals("FAILED"));
  }

  @Test
  void failsABackupThatStopsReporting() throws Exception {
    var id = startBackup();
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> backup(id).get("status").asString().equals("FAILED"));
    assertThat(backup(id).get("error").asString()).contains("stopped reporting");
  }

  @Test
  void runsOneBackupOfAWorkspaceAtATime() throws Exception {
    startBackup();
    assertThat(send("POST", "/workspaces/" + workspace + "/backups", null).statusCode())
        .isEqualTo(409);
  }

  @Test
  void asksForARestartWhenTheHarnessPredatesBackups() throws Exception {
    OPENCODE.shellExit = 1;
    var response = send("POST", "/workspaces/" + workspace + "/backups", null);
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.body()).contains("restart");
    assertThat(backups.findAll())
        .singleElement()
        .extracting("status")
        .isEqualTo(BackupStatus.FAILED);
  }

  @Test
  void keepsBackupsOfADeletedWorkspaceUnderItsLastName() throws Exception {
    var id = completedBackup();
    workspaces.delete(workspace);
    var listed = JSON.readTree(send("GET", "/backups", null).body());
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).get("id").asString()).isEqualTo(id);
    assertThat(listed.get(0).get("workspaceName").asString()).isEqualTo("Research");
    assertThat(listed.get(0).get("workspaceExists").asBoolean()).isFalse();
    assertThat(send("POST", "/backups/" + id + "/restore", null).statusCode()).isEqualTo(404);
  }

  @Test
  void restoresTheSettingsAndConversationsInPlace() throws Exception {
    sessions.save(new WorkspaceSessionEntity(UUID.randomUUID(), workspace, "/kept", "ses_kept"));
    var id = completedBackup();
    repository.updateModel(workspace, "anthropic/claude-sonnet-5.5");
    sessions.save(new WorkspaceSessionEntity(UUID.randomUUID(), workspace, "/later", "ses_later"));

    assertThat(send("POST", "/backups/" + id + "/restore", null).statusCode()).isEqualTo(202);
    assertThat(repository.findById(workspace).orElseThrow().getModel()).isNull();
    assertThat(sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspace))
        .singleElement()
        .extracting(WorkspaceSessionEntity::getHarnessSessionId)
        .isEqualTo("ses_kept");
    verify(storage).downloadUrl(anyString(), any());
  }

  @Test
  void reportsNoRestoreProgressForAWorkspaceThatIsNotRestoring() throws Exception {
    assertThat(send("GET", "/workspaces/" + workspace + "/restore", null).statusCode())
        .isEqualTo(204);
  }

  @Test
  void restoresOnlyACompletedBackup() throws Exception {
    var id = startBackup();
    assertThat(send("POST", "/backups/" + id + "/restore", null).statusCode()).isEqualTo(409);
    assertThat(send("POST", "/backups/" + id + "/workspaces", "{\"name\":\"Copy\"}").statusCode())
        .isEqualTo(409);
  }

  @Test
  void createsANewWorkspaceFromABackup() throws Exception {
    sessions.save(new WorkspaceSessionEntity(UUID.randomUUID(), workspace, "/kept", "ses_kept"));
    repository.updateModel(workspace, "anthropic/claude-sonnet-5.5");
    var id = completedBackup();

    var created =
        send("POST", "/backups/" + id + "/workspaces", "{\"name\":\" Copy \",\"apiKey\":\"sk-2\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    var copy = JSON.readTree(created.body());
    assertThat(copy.get("name").asString()).isEqualTo("Copy");
    assertThat(copy.get("model").asString()).isEqualTo("anthropic/claude-sonnet-5.5");
    var copyId = UUID.fromString(copy.get("id").asString());
    assertThat(sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(copyId))
        .singleElement()
        .extracting(WorkspaceSessionEntity::getPath)
        .isEqualTo("/kept");
  }

  @Test
  void signsADownloadOfACompletedBackup() throws Exception {
    var running = startBackup();
    assertThat(send("POST", "/backups/" + running + "/download", null).statusCode()).isEqualTo(409);
    report(running, "done", 1, 1);
    await().until(() -> backup(running).get("status").asString().equals("COMPLETED"));
    var download = JSON.readTree(send("POST", "/backups/" + running + "/download", null).body());
    assertThat(download.get("url").asString()).isEqualTo("https://s3/down?sig=2");
    assertThat(download.get("expiresAt").isNull()).isFalse();
  }

  @Test
  void needsAKeyWhenNoneCanBeReused() throws Exception {
    var id = completedBackup();
    // The local runtime keeps no secrets, so there is no key to reuse.
    assertThat(send("POST", "/backups/" + id + "/workspaces", "{\"name\":\"Copy\"}").statusCode())
        .isEqualTo(400);
    workspaces.delete(workspace);
    assertThat(send("POST", "/backups/" + id + "/workspaces", "{\"name\":\"Copy\"}").statusCode())
        .isEqualTo(400);
  }

  @Test
  void deletesTheArchiveWithTheBackup() throws Exception {
    var running = startBackup();
    assertThat(send("DELETE", "/backups/" + running, null).statusCode()).isEqualTo(409);
    report(running, "done", 1, 1);
    await().until(() -> backup(running).get("status").asString().equals("COMPLETED"));
    assertThat(send("DELETE", "/backups/" + running, null).statusCode()).isEqualTo(204);
    verify(storage).delete(anyString());
    assertThat(send("GET", "/backups/" + running, null).statusCode()).isEqualTo(404);
  }

  @Test
  void answersNotFoundForSomeoneElsesBackup() throws Exception {
    var id = completedBackup();
    var username = "u" + UUID.randomUUID().toString().substring(0, 8);
    send(
        "POST",
        "/users",
        "{\"username\":\""
            + username
            + "\",\"password\":\"password1\","
            + "\"firstName\":\"\",\"lastName\":\"\"}");
    var other = "Bearer " + TestUsers.login(port, username, "password1");
    assertThat(send("GET", "/backups/" + id, null, other).statusCode()).isEqualTo(404);
    assertThat(send("DELETE", "/backups/" + id, null, other).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/backups/" + id + "/restore", null, other).statusCode())
        .isEqualTo(404);
    assertThat(JSON.readTree(send("GET", "/backups", null, other).body())).isEmpty();
    assertThat(send("GET", "/backups/" + id, null).statusCode()).isEqualTo(200);
  }

  private String startBackup() throws Exception {
    var started = send("POST", "/workspaces/" + workspace + "/backups", null);
    assertThat(started.statusCode()).isEqualTo(202);
    return JSON.readTree(started.body()).get("id").asString();
  }

  private String completedBackup() throws Exception {
    var id = startBackup();
    report(id, "done", 1, 1);
    await().until(() -> backup(id).get("status").asString().equals("COMPLETED"));
    return id;
  }

  private void report(String id, String phase, long done, long total) {
    OPENCODE.files.put(
        STATUS,
        "{\"id\":\""
            + id
            + "\",\"phase\":\""
            + phase
            + "\",\"done\":"
            + done
            + ",\"total\":"
            + total
            + "}");
  }

  private JsonNode backup(String id) throws Exception {
    return JSON.readTree(send("GET", "/backups/" + id, null).body());
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    return send(method, path, json, TestUsers.bearer(port));
  }

  private HttpResponse<String> send(String method, String path, String json, String auth)
      throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
