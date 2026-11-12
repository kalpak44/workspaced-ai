package io.github.kalpak44.workspacedai.api.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Against a server that answers HEAD and DELETE as S3 does, path-style. */
class S3BackupStorageTest {

  private final List<String> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private S3BackupStorage storage;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          requests.add(exchange.getRequestMethod() + " " + path);
          if (exchange.getRequestMethod().equals("HEAD") && path.endsWith("/there.tar.gz")) {
            exchange.getResponseHeaders().add("Content-Length", "42");
            exchange.sendResponseHeaders(200, -1);
          } else if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(404, -1);
          } else {
            exchange.sendResponseHeaders(204, -1);
          }
          exchange.close();
        });
    server.start();
    var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    storage =
        new S3BackupStorage(
            new BackupProperties(
                endpoint,
                "backups",
                "us-east-1",
                "key",
                "secret",
                Duration.ofHours(1),
                Duration.ofDays(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(1)));
  }

  @AfterEach
  void stop() {
    storage.close();
    server.stop(0);
  }

  @Test
  void signsPathStyleUrlsThatExpire() {
    var up = storage.uploadUrl("users/a/b.tar.gz", Duration.ofMinutes(5));
    assertThat(up.getPath()).isEqualTo("/backups/users/a/b.tar.gz");
    assertThat(up.getQuery()).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");
    var down = storage.downloadUrl("users/a/b.tar.gz", Duration.ofMinutes(5));
    assertThat(down.getPath()).isEqualTo("/backups/users/a/b.tar.gz");
    assertThat(down).isNotEqualTo(up);
  }

  @Test
  void readsTheSizeAndDeletes() {
    assertThat(storage.size("there.tar.gz")).hasValue(42);
    assertThat(storage.size("missing.tar.gz")).isEmpty();
    storage.delete("there.tar.gz");
    assertThat(requests).contains("DELETE /backups/there.tar.gz");
  }

  @Test
  void answersUnavailableWhileUnconfigured() {
    var unconfigured = new UnconfiguredBackupStorage();
    assertThatThrownBy(() -> unconfigured.uploadUrl("k", Duration.ZERO))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> unconfigured.downloadUrl("k", Duration.ZERO))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> unconfigured.size("k")).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> unconfigured.delete("k")).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void sharesProgressBetweenTheTwoPhases() {
    assertThat(BackupProgress.share(50, 100, 1, 80)).isEqualTo(40);
    assertThat(BackupProgress.share(200, 100, 80, 99)).isEqualTo(99);
    assertThat(BackupProgress.share(0, 0, 1, 80)).isEqualTo(80);
  }
}
