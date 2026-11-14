package io.github.kalpak44.workspacedai.api.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class RecipesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();
  private String admin;
  private String ada;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM recipe");
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    users.save(new UserEntity("ada", passwords.encode("password1"), "Ada", "Lovelace"));
    admin = TestUsers.bearer(port);
    ada = "Bearer " + TestUsers.login(port, "ada", "password1");
  }

  @Test
  void createsARecipeWithItsFieldsNormalized() throws Exception {
    var created =
        send(
            "POST",
            "/recipes",
            body(" WordPress ", "PUBLIC", "websites", List.of(" WordPress", "docker", "wordpress")),
            ada);
    assertThat(created.statusCode()).isEqualTo(201);
    var recipe = JSON.readTree(created.body());
    assertThat(created.headers().firstValue("Location").orElseThrow())
        .endsWith("/api/v1/recipes/" + recipe.get("id").asString());
    assertThat(recipe.get("title").asString()).isEqualTo("WordPress");
    assertThat(strings(recipe.get("tags"))).containsExactly("wordpress", "docker");
    assertThat(recipe.get("author").get("username").asString()).isEqualTo("ada");
    assertThat(recipe.get("author").get("firstName").asString()).isEqualTo("Ada");
    assertThat(recipe.get("mine").asBoolean()).isTrue();
    assertThat(recipe.get("publishedAt").isNull()).isFalse();
    assertThat(recipe.get("rating").get("count").asInt()).isZero();
    assertThat(recipe.get("myRating").isNull()).isTrue();
    assertThat(recipe.get("content").asString()).isEqualTo("1. Install it.");
  }

  @Test
  void refusesInvalidFields() throws Exception {
    var invalid =
        List.of(
            body(" ", "PUBLIC", "apps", List.of()),
            body("x".repeat(101), "PUBLIC", "apps", List.of()),
            body("Fine", "PUBLIC", "games", List.of()),
            body("Fine", "PUBLIC", "apps", List.of("not a tag")),
            body("Fine", "PUBLIC", "apps", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")),
            body("Fine", "SHARED", "apps", List.of()),
            "{\"title\":\"Fine\",\"category\":\"apps\",\"visibility\":\"PUBLIC\",\"content\":\"\"}");
    for (var request : invalid) {
      assertThat(send("POST", "/recipes", request, ada).statusCode()).as(request).isEqualTo(400);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe", Long.class)).isZero();
  }

  @Test
  void listsPublicRecipesForEveryoneAndPrivateOnesForTheirAuthor() throws Exception {
    var shared = id(create("Shared", "PUBLIC", ada));
    var secret = id(create("Secret", "PRIVATE", ada));
    create("Admin's", "PUBLIC", admin);

    assertThat(titles(list("?scope=public", admin))).containsExactlyInAnyOrder("Shared", "Admin's");
    assertThat(titles(list("?scope=private", ada))).containsExactly("Secret");
    assertThat(titles(list("?scope=private", admin))).isEmpty();
    assertThat(send("GET", "/recipes/" + secret, null, admin).statusCode()).isEqualTo(404);
    assertThat(send("GET", "/recipes/" + secret, null, ada).statusCode()).isEqualTo(200);
    var seen = JSON.readTree(send("GET", "/recipes/" + shared, null, admin).body());
    assertThat(seen.get("mine").asBoolean()).isFalse();
    assertThat(list("?scope=everyone", ada)).isNull();
  }

  @Test
  void searchesTitlesSummariesAndTagsAndFiltersByCategory() throws Exception {
    send("POST", "/recipes", body("WordPress", "PUBLIC", "websites", List.of("php")), ada);
    send("POST", "/recipes", body("Nightly backup", "PUBLIC", "automation", List.of("cron")), ada);
    send("POST", "/recipes", body("100% uptime", "PUBLIC", "devops", List.of()), ada);

    assertThat(titles(list("?q=PHP", ada))).containsExactly("WordPress");
    assertThat(titles(list("?q=nightly", ada))).containsExactly("Nightly backup");
    assertThat(titles(list("?q=%25", ada))).containsExactly("100% uptime");
    assertThat(titles(list("?category=automation", ada))).containsExactly("Nightly backup");
    var page = list("?size=2", ada);
    assertThat(page.get("content")).hasSize(2);
    assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(3);
    assertThat(page.get("page").get("totalPages").asInt()).isEqualTo(2);
  }

  @Test
  void ordersByRatingNewestOrLastUpdate() throws Exception {
    var first = id(create("First", "PUBLIC", admin));
    var second = id(create("Second", "PUBLIC", admin));
    send("PUT", "/recipes/" + first + "/rating", "{\"stars\":4}", ada);
    send("PUT", "/recipes/" + second + "/rating", "{\"stars\":2}", ada);

    assertThat(titles(list("?sort=top", ada))).containsExactly("First", "Second");
    assertThat(titles(list("?sort=newest", ada))).containsExactly("Second", "First");
    send("PUT", "/recipes/" + first, body("First", "PUBLIC", "apps", List.of()), admin);
    assertThat(titles(list("?sort=updated", ada))).containsExactly("First", "Second");
    assertThat(list("?sort=random", ada)).isNull();
  }

  @Test
  void onlyTheAuthorChangesARecipeAndAnAdminMayDeleteIt() throws Exception {
    var shared = id(create("Shared", "PUBLIC", ada));
    var secret = id(create("Secret", "PRIVATE", ada));
    var change = body("Changed", "PUBLIC", "data", List.of());

    assertThat(send("PUT", "/recipes/" + shared, change, admin).statusCode()).isEqualTo(403);
    assertThat(send("PUT", "/recipes/" + secret, change, admin).statusCode()).isEqualTo(404);
    var changed = send("PUT", "/recipes/" + secret, change, ada);
    assertThat(changed.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(changed.body()).get("publishedAt").isNull()).isFalse();

    var adminsOwn = id(create("Admin's", "PUBLIC", admin));
    assertThat(send("DELETE", "/recipes/" + adminsOwn, null, ada).statusCode()).isEqualTo(403);
    assertThat(send("DELETE", "/recipes/" + shared, null, admin).statusCode()).isEqualTo(204);
    assertThat(send("DELETE", "/recipes/" + secret, null, ada).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/recipes/" + secret, null, ada).statusCode()).isEqualTo(404);
  }

  @Test
  void ratesSomeoneElsesPublicRecipeOnceEach() throws Exception {
    var recipe = id(create("Shared", "PUBLIC", ada));
    users.save(new UserEntity("grace", passwords.encode("password1")));
    var grace = "Bearer " + TestUsers.login(port, "grace", "password1");

    send("PUT", "/recipes/" + recipe + "/rating", "{\"stars\":5}", admin);
    var rated = send("PUT", "/recipes/" + recipe + "/rating", "{\"stars\":3}", admin);
    assertThat(rated.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(rated.body()).get("myRating").asInt()).isEqualTo(3);
    var both =
        JSON.readTree(send("PUT", "/recipes/" + recipe + "/rating", "{\"stars\":4}", grace).body());
    assertThat(both.get("average").asDouble()).isEqualTo(3.5);
    assertThat(both.get("count").asInt()).isEqualTo(2);

    assertThat(send("PUT", "/recipes/" + recipe + "/rating", "{\"stars\":6}", grace).statusCode())
        .isEqualTo(400);
    assertThat(send("PUT", "/recipes/" + recipe + "/rating", "{\"stars\":5}", ada).statusCode())
        .isEqualTo(409);
    var secret = id(create("Secret", "PRIVATE", ada));
    assertThat(send("PUT", "/recipes/" + secret + "/rating", "{\"stars\":5}", admin).statusCode())
        .isEqualTo(404);

    assertThat(send("DELETE", "/recipes/" + recipe + "/rating", null, grace).statusCode())
        .isEqualTo(204);
    assertThat(send("DELETE", "/recipes/" + recipe + "/rating", null, grace).statusCode())
        .isEqualTo(204);
    var after = JSON.readTree(send("GET", "/recipes/" + recipe, null, admin).body());
    assertThat(after.get("rating").get("average").asDouble()).isEqualTo(3.0);
    assertThat(after.get("myRating").asInt()).isEqualTo(3);
  }

  @Test
  void ratingsSentAtOnceByOneUserLeaveOneRating() throws Exception {
    var recipe = id(create("Shared", "PUBLIC", ada));
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var sent =
          IntStream.rangeClosed(1, 5)
              .mapToObj(
                  stars ->
                      threads.submit(
                          () ->
                              send(
                                  "PUT",
                                  "/recipes/" + recipe + "/rating",
                                  "{\"stars\":" + stars + "}",
                                  admin)))
              .toList();
      for (var response : sent) {
        assertThat(response.get().statusCode()).isEqualTo(200);
      }
    }
    var rated = JSON.readTree(send("GET", "/recipes/" + recipe, null, admin).body());
    assertThat(rated.get("rating").get("count").asInt()).isEqualTo(1);
  }

  @Test
  void refusesAPagePastTheLargestOffset() throws Exception {
    var path = "/recipes?page=" + Integer.MAX_VALUE + "&size=100";
    assertThat(send("GET", path, null, ada).statusCode()).isEqualTo(400);
  }

  @Test
  void deletingTheAuthorDeletesTheirRecipes() throws Exception {
    create("Shared", "PUBLIC", ada);
    users.delete(users.findByUsername("ada").orElseThrow());
    assertThat(list("", admin).get("content")).isEmpty();
  }

  private JsonNode create(String title, String visibility, String bearer) throws Exception {
    var response = send("POST", "/recipes", body(title, visibility, "apps", List.of()), bearer);
    assertThat(response.statusCode()).isEqualTo(201);
    return JSON.readTree(response.body());
  }

  private static String id(JsonNode recipe) {
    return recipe.get("id").asString();
  }

  private static String body(String title, String visibility, String category, List<String> tags) {
    return JSON.writeValueAsString(
        Map.of(
            "title", title,
            "summary", "",
            "category", category,
            "tags", tags,
            "visibility", visibility,
            "content", "1. Install it."));
  }

  /** The page, or null when the listing was refused. */
  private JsonNode list(String query, String bearer) throws Exception {
    var response = send("GET", "/recipes" + query, null, bearer);
    return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
  }

  private static List<String> titles(JsonNode page) {
    var titles = new ArrayList<String>();
    page.get("content").forEach(r -> titles.add(r.get("title").asString()));
    return titles;
  }

  private static List<String> strings(JsonNode array) {
    var values = new ArrayList<String>();
    array.forEach(v -> values.add(v.asString()));
    return values;
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer);
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
