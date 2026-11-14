package io.github.kalpak44.workspacedai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class DatabaseHealthTest {

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @Test
  void connectsToPostgres() throws Exception {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
    }
  }

  @Test
  void reportsReadyOnlyWithTheDatabase() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/actuator/health/readiness"))
            .build();
    var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }
}
