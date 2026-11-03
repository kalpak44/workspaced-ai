package io.github.kalpak44.workspacedai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class OpenApiTest {

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void publishesTheWorkspaceEndpoints() throws Exception {
    var response = get("/api/v1/v3/api-docs");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains(
            "\"openapi\":\"3.0",
            "/workspaces/{id}",
            "\"operationId\":\"createWorkspace\"",
            "\"operationId\":\"renameWorkspace\"",
            "\"operationId\":\"deleteWorkspace\"",
            "\"operationId\":\"listWorkspaces\"",
            "\"operationId\":\"listModelProviders\"",
            "\"operationId\":\"addWorkspaceSecret\"",
            "\"operationId\":\"removeWorkspaceSecret\"",
            "application/problem+json");
  }

  @Test
  void servesSwaggerUi() throws Exception {
    assertThat(get("/api/v1/swagger-ui/index.html").statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> get(String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return http.send(request, BodyHandlers.ofString());
  }
}
