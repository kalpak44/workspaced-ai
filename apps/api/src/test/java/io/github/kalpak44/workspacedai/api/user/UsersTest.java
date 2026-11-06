package io.github.kalpak44.workspacedai.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class UsersTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired PasswordEncoder passwords;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    workspaces.deleteAll();
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void listsUsersAPageAtATimeNewestFirst() throws Exception {
    for (var name : new String[] {"ada", "grace", "linus"}) {
      users.saveAndFlush(new UserEntity(name, "{noop}x"));
    }
    var first = list("?size=2");
    assertThat(usernames(first)).containsExactly("linus", "grace");
    assertThat(first.get("page").get("totalElements").asInt()).isEqualTo(4);
    assertThat(first.get("page").get("totalPages").asInt()).isEqualTo(2);
    assertThat(usernames(list("?size=2&page=1"))).containsExactly("ada", "admin");
  }

  @Test
  void sortsByTheFieldsAUserHas() throws Exception {
    users.save(new UserEntity("zed", "{noop}x", "Ada", "Byron"));
    users.save(new UserEntity("ada", "{noop}x", "Ada", "Lovelace"));
    users.save(new UserEntity("grace", "{noop}x", "Grace", "Hopper"));
    assertThat(usernames(list("?sort=username,desc")))
        .containsExactly("zed", "grace", "admin", "ada");
    assertThat(usernames(list("?sort=firstName,asc&sort=lastName,asc")))
        .containsExactly("admin", "zed", "ada", "grace");
    assertThat(send("GET", "/users?sort=passwordHash,asc", null).statusCode()).isEqualTo(400);
  }

  @Test
  void capsThePageSize() throws Exception {
    assertThat(list("?size=5000").get("page").get("size").asInt()).isEqualTo(100);
  }

  @Test
  void createsAUserWhoCanSignIn() throws Exception {
    var created =
        send(
            "POST",
            "/users",
            "{\"username\":\" ada \",\"password\":\"password1\",\"firstName\":\"Ada\",\"lastName\":\" Lovelace\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    var body = JSON.readTree(created.body());
    assertThat(body.get("username").asString()).isEqualTo("ada");
    assertThat(body.get("lastName").asString()).isEqualTo("Lovelace");
    assertThat(body.has("passwordHash")).isFalse();
    assertThat(created.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/users/" + body.get("id").asString()));
    assertThat(TestUsers.login(port, "ada", "password1")).isNotBlank();
  }

  @Test
  void refusesATakenUsernameOrABadPassword() throws Exception {
    var taken =
        "{\"username\":\"admin\",\"password\":\"password1\",\"firstName\":\"\",\"lastName\":\"\"}";
    assertThat(send("POST", "/users", taken).statusCode()).isEqualTo(409);
    var shortPassword =
        "{\"username\":\"ada\",\"password\":\"short\",\"firstName\":\"\",\"lastName\":\"\"}";
    assertThat(send("POST", "/users", shortPassword).statusCode()).isEqualTo(400);
    assertThat(users.findByUsername("ada")).isEmpty();
  }

  @Test
  void deletesAnotherUserWithTheirWorkspaces() throws Exception {
    users.save(new UserEntity("ada", passwords.encode("password1")));
    var ada = "Bearer " + TestUsers.login(port, "ada", "password1");
    var workspace =
        send(
            "POST",
            "/workspaces",
            "{\"name\":\"Ada's\",\"modelProvider\":\"openrouter\",\"apiKey\":\"sk-or-1\"}",
            ada);
    assertThat(workspace.statusCode()).isEqualTo(201);
    var id = users.findByUsername("ada").orElseThrow().getId();

    assertThat(send("DELETE", "/users/" + id, null).statusCode()).isEqualTo(204);
    assertThat(users.findById(id)).isEmpty();
    assertThat(workspaces.findAll()).isEmpty();
    assertThat(send("GET", "/account", null, ada).statusCode()).isEqualTo(401);
    assertThat(send("DELETE", "/users/" + id, null).statusCode()).isEqualTo(404);
  }

  @Test
  void refusesToDeleteTheSignedInUser() throws Exception {
    assertThat(send("DELETE", "/users/" + TestUsers.ADMIN, null).statusCode()).isEqualTo(409);
    assertThat(users.findById(TestUsers.ADMIN)).isPresent();
  }

  @Test
  void needsASignedInUser() throws Exception {
    var request = HttpRequest.newBuilder(url("/users")).build();
    assertThat(http.send(request, BodyHandlers.ofString()).statusCode()).isEqualTo(401);
  }

  private JsonNode list(String query) throws Exception {
    var response = send("GET", "/users" + query, null);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static ArrayList<String> usernames(JsonNode page) {
    var names = new ArrayList<String>();
    page.get("content").forEach(u -> names.add(u.get("username").asString()));
    return names;
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    return send(method, path, body, TestUsers.bearer(port));
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer)
      throws Exception {
    var builder = HttpRequest.newBuilder(url(path)).header("Authorization", bearer);
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    var request =
        builder
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
