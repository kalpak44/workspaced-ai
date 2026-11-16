package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class WorkspaceMetricsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @MockitoBean VmManager vmManager;
  @Autowired WorkspaceRepository workspaces;

  private final HttpClient http = HttpClient.newHttpClient();
  UUID workspace;

  @BeforeEach
  void setUp() {
    workspace =
        workspaces
            .save(new WorkspaceEntity(TestUsers.ADMIN, "Metrics", "openrouter", "UTC"))
            .getId();
  }

  @Test
  void answersTheVmManagersMetricsForTheRangeAskedFor() throws Exception {
    var at = Instant.parse("2026-11-14T10:00:00Z");
    when(vmManager.metricsFor(workspace, "24h"))
        .thenReturn(
            new WorkspaceMetrics(
                2,
                4.0,
                List.of(at),
                Map.of("cpu", List.of(12.5)),
                new WorkspaceMetrics.Memory(4096, 1024, at)));

    var response = get(workspace, "?range=24h");

    assertThat(response.statusCode()).isEqualTo(200);
    var metrics = JSON.readTree(response.body());
    assertThat(metrics.path("series").path("cpu").get(0).asDouble()).isEqualTo(12.5);
    assertThat(metrics.path("memory").path("totalBytes").asLong()).isEqualTo(4096);
  }

  @Test
  void answersConflictForAWorkspaceWithNoVmYet() throws Exception {
    when(vmManager.metricsFor(workspace, "live")).thenThrow(new IllegalStateException("no VM"));
    assertThat(get(workspace, "").statusCode()).isEqualTo(409);
  }

  @Test
  void answersNotFoundForAWorkspaceThatDoesNotExist() throws Exception {
    assertThat(get(UUID.randomUUID(), "").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> get(UUID id, String query) throws Exception {
    var url = "http://localhost:" + port + "/api/v1/workspaces/" + id + "/metrics" + query;
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", TestUsers.bearer(port))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
