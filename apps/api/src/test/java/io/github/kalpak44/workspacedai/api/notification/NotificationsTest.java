package io.github.kalpak44.workspacedai.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.AuthService;
import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A new user is welcomed, and reads and unreads only their own notifications. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class NotificationsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired NotificationService notifications;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID user;
  private String bearer;

  @BeforeEach
  void signIn() {
    var subject = "auth0|" + UUID.randomUUID();
    bearer =
        "Bearer "
            + auth.signIn(subject, subject.substring(6) + "@example.com", true, Role.USER)
                .token()
                .value();
    user = users.findByIdpSubject(subject).orElseThrow().getId();
  }

  @Test
  void welcomesANewUser() throws Exception {
    var list = read(send("GET", "/notifications", null));

    assertThat(list).hasSize(1);
    var welcome = list.get(0);
    assertThat(welcome.get("title").asString()).isEqualTo("Welcome to workspaced");
    assertThat(welcome.get("kind").asString()).isEqualTo("SYSTEM");
    assertThat(welcome.get("body").asString()).startsWith("Welcome!");
    assertThat(welcome.get("read").asBoolean()).isFalse();
    assertThat(welcome.get("action").get("path").asString()).isEqualTo("/billing");
  }

  @Test
  void marksItsOwnNotificationsReadAndUnreadAndLeavesOthersAlone() throws Exception {
    var own = notifications.list(user).getFirst().id();
    notifications.send(TestUsers.ADMIN, Notifications.workspaceDeleted("theirs", false));
    var others = notifications.list(TestUsers.ADMIN).getFirst().id();

    var read = read(send("PUT", "/notifications/read", body(List.of(own, others), true)));

    assertThat(read.get(0).get("read").asBoolean()).isTrue();
    assertThat(notifications.list(TestUsers.ADMIN).getFirst().read()).isFalse();
    var unread = read(send("PUT", "/notifications/read", body(List.of(own), false)));
    assertThat(unread.get(0).get("read").asBoolean()).isFalse();
  }

  @Test
  void refusesARequestWithoutIds() throws Exception {
    assertThat(send("PUT", "/notifications/read", "{\"read\":true}").statusCode()).isEqualTo(400);
  }

  @Test
  void sendsAKeyedReminderOnce() {
    var message = Notifications.fundsRunningOut("box", 2, Duration.ofDays(3), "5.00 EUR");

    notifications.sendOnce(user, "key", message);
    notifications.sendOnce(user, "key", message);

    assertThat(notifications.list(user)).hasSize(2);
  }

  private static String body(List<UUID> ids, boolean read) {
    return JSON.writeValueAsString(Map.of("ids", ids, "read", read));
  }

  private static JsonNode read(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer);
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return http.send(
        builder
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build(),
        BodyHandlers.ofString());
  }
}
