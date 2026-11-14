package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Iterator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class WorkspaceEventsTest {

  @LocalServerPort int port;
  @Autowired WorkspaceService service;
  @Autowired WorkspaceEvents events;
  @Autowired WorkspaceRepository repository;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ApiKeyCheck keyCheck;

  @BeforeEach
  void emptyTable() {
    repository.deleteAll();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  @Test
  @Timeout(10)
  void sendsASnapshotOnConnectThenEachChange() throws Exception {
    var workspace =
        service.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-test", null);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces/events"))
            .header("Authorization", TestUsers.bearer(port))
            .header("Accept", "text/event-stream")
            .build();
    var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofLines());
    assertThat(response.statusCode()).isEqualTo(200);
    var lines = response.body().iterator();

    assertThat(nextData(lines)).contains(workspace.id().toString(), "\"READY\"");

    events.heartbeat();
    assertThat(nextNonBlank(lines)).isEqualTo(":keepalive");

    events.publish(workspace.id(), WorkspaceStatus.FAILED);
    assertThat(nextData(lines)).contains(workspace.id().toString(), "\"FAILED\"");

    service.delete(workspace.id());
    assertThat(nextNonBlank(lines)).isEqualTo("event:" + WorkspaceEvents.DELETED);
    assertThat(lines.next()).contains(workspace.id().toString());

    // Shutdown ends the stream instead of leaving graceful shutdown to wait on it.
    events.closeAll();
    while (lines.hasNext()) {
      assertThat(lines.next()).isEmpty();
    }
  }

  /**
   * A deletion another API instance handled reaches this instance's browsers through the database,
   * as a NOTIFY committed by that instance.
   */
  @Test
  @Timeout(10)
  void relaysADeletionAnotherInstanceAnnounced() throws Exception {
    // A workspace of its own, so the stream opens with a snapshot rather than on a heartbeat.
    var mine = service.create(TestUsers.ADMIN, "Mine", ModelProvider.OPENROUTER, "sk-test", null);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces/events"))
            .header("Authorization", TestUsers.bearer(port))
            .header("Accept", "text/event-stream")
            .build();
    var lines = HttpClient.newHttpClient().send(request, BodyHandlers.ofLines()).body().iterator();
    assertThat(nextData(lines)).contains(mine.id().toString());
    var elsewhere = UUID.randomUUID();
    // Someone else's workspace first: it must not reach this user's stream.
    jdbc.query(
        "select pg_notify(?, ?)",
        (RowCallbackHandler) _ -> {},
        WorkspaceDeletions.CHANNEL,
        UUID.randomUUID() + ":" + UUID.randomUUID());
    jdbc.query(
        "select pg_notify(?, ?)",
        (RowCallbackHandler) _ -> {},
        WorkspaceDeletions.CHANNEL,
        elsewhere + ":" + TestUsers.ADMIN);

    assertThat(nextNonBlank(lines)).isEqualTo("event:" + WorkspaceEvents.DELETED);
    assertThat(lines.next()).contains(elsewhere.toString());
  }

  private static String nextNonBlank(Iterator<String> lines) {
    var line = lines.next();
    while (line.isEmpty()) {
      line = lines.next();
    }
    return line;
  }

  /** Skips to the next status event and returns its data line. */
  private static String nextData(Iterator<String> lines) {
    while (lines.hasNext()) {
      var line = lines.next();
      if (line.equals("event:" + WorkspaceEvents.STATUS)) {
        return lines.next();
      }
    }
    throw new AssertionError("stream ended");
  }
}
