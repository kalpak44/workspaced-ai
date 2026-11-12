package io.github.kalpak44.workspacedai.api.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.AuthProperties;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.session.SessionService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class SessionFilesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired SessionService sessions;
  @Autowired SessionFileService files;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID workspace;
  private UUID session;
  private String folder;

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
    OPENCODE.shellExit = 0;
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1").id();
    var created = sessions.create(workspace);
    session = created.id();
    folder = created.path();
  }

  @Test
  void uploadsIntoTheSessionsOwnFolder() throws Exception {
    var response = send("PUT", files("/notes.txt"), "hello");
    assertThat(response.statusCode()).isEqualTo(201);
    var uploaded = JSON.readTree(response.body());
    assertThat(uploaded.get("path").asString()).isEqualTo("uploads/notes.txt");
    assertThat(uploaded.get("size").asLong()).isEqualTo(5);
    assertThat(OPENCODE.files).containsEntry(folder + "/uploads/notes.txt", "hello");
    assertThat(OPENCODE.shellCommands).contains("mkdir -p -- '" + folder + "/uploads'");
  }

  @Test
  void refusesANameThatIsNotAPlainFileName() throws Exception {
    for (var name : new String[] {".env", "a..b", "%24HOME"}) {
      assertThat(send("PUT", files("/" + name), "x").statusCode()).as(name).isEqualTo(400);
    }
  }

  @Test
  void refusesAnUploadWithoutALengthOrTooLarge() {
    var empty = new ByteArrayInputStream(new byte[0]);
    assertThatThrownBy(() -> files.upload(workspace, session, "a.txt", empty, -1))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(411));
    assertThatThrownBy(
            () ->
                files.upload(workspace, session, "a.txt", empty, SessionFileService.MAX_UPLOAD + 1))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(413));
  }

  @Test
  void linksAFileTheAgentMadeUntilTheSessionIsGone() throws Exception {
    OPENCODE.files.put(folder + "/outbox/report.txt", "the report");
    var link = send("POST", files("/links"), "{\"path\":\"outbox/report.txt\"}");
    assertThat(link.statusCode()).isEqualTo(200);
    var url = JSON.readTree(link.body()).get("url").asString();
    assertThat(url).startsWith("http://localhost:" + port + "/api/v1/files/");

    // No sign-in: the token is the credential.
    var download =
        http.send(HttpRequest.newBuilder(URI.create(url)).build(), BodyHandlers.ofString());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).isEqualTo("the report");
    assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment")
        .contains("report.txt");

    sessions.delete(workspace, session);
    var gone = http.send(HttpRequest.newBuilder(URI.create(url)).build(), BodyHandlers.ofString());
    assertThat(gone.statusCode()).isEqualTo(404);
  }

  @Test
  void acceptsAnAbsolutePathInsideTheFolderOnly() throws Exception {
    OPENCODE.files.put(folder + "/out.csv", "a,b");
    assertThat(send("POST", files("/links"), "{\"path\":\"" + folder + "/out.csv\"}").statusCode())
        .isEqualTo(200);
    assertThat(send("POST", files("/links"), "{\"path\":\"../../.env\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", files("/links"), "{\"path\":\"/etc/passwd\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", files("/links"), "{\"path\":\"missing.txt\"}").statusCode())
        .isEqualTo(404);
  }

  @Test
  void answersNotFoundForAForgedLink() throws Exception {
    var forged =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/files/e30.c2ln"))
                .build(),
            BodyHandlers.ofString());
    assertThat(forged.statusCode()).isEqualTo(404);
  }

  @Test
  void signsTokensThatExpireAndCannotBeAltered() {
    var auth = new AuthProperties("k".repeat(32), Duration.ofHours(1));
    var now = Instant.parse("2026-11-12T10:00:00Z");
    var links = new FileLinks(auth, JSON, Clock.fixed(now, ZoneOffset.UTC));
    var token = links.sign(workspace, session, "a.txt", now.plusSeconds(60));
    assertThat(links.verify(token))
        .hasValueSatisfying(g -> assertThat(g.path()).isEqualTo("a.txt"));
    assertThat(links.verify(token.replace('.', 'x'))).isEmpty();
    assertThat(links.verify("x" + token)).isEmpty();
    assertThat(links.verify(links.sign(workspace, session, "a.txt", now.minusSeconds(1))))
        .isEmpty();
    var other = new FileLinks(new AuthProperties("o".repeat(32), Duration.ofHours(1)), JSON);
    assertThat(other.verify(token)).isEmpty();
  }

  private String files(String rest) {
    return "/workspaces/" + workspace + "/sessions/" + session + "/files" + rest;
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header(
                "Content-Type",
                method.equals("PUT") ? "application/octet-stream" : "application/json")
            .method(method, BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
