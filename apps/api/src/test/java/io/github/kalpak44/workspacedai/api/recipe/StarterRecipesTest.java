package io.github.kalpak44.workspacedai.api.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A separate database context keeps the catalogue intact while CRUD tests delete their fixtures.
 */
@SpringBootTest(properties = "workspaced.test.starter-catalogue=true")
@Import({TestDatabase.class, TestRuntime.class})
class StarterRecipesTest {
  private static final Map<String, String> SOURCES =
      Map.of(
          "simple-web-page",
          "b00d",
          "wordpress",
          "b001",
          "gmail",
          "b006",
          "google-calendar",
          "b007",
          "ciela-mcp",
          "b008",
          "telegram-remote-control",
          "b009",
          "telegram-personal-account",
          "b00a",
          "godaddy-domain",
          "b00b",
          "authenticated-browser",
          "b00c");

  private static final UUID CIELA = UUID.fromString("00000000-0000-4000-8000-00000000b008");
  private static final UUID BROWSER = UUID.fromString("00000000-0000-4000-8000-00000000b00c");
  private static final UUID PUBLISHER = UUID.fromString("00000000-0000-4000-8000-000000000a01");

  @Autowired RecipeService recipes;
  @Autowired StarterRecipes starterRecipes;
  @Autowired UserRepository users;

  @Test
  void seedsEverySourceRecipeWithItsCodeUnchanged() throws IOException {
    var viewer = UUID.randomUUID();
    var publicRecipes = recipes.list(viewer, "public", "", null, "newest", 0, 100);
    assertThat(publicRecipes.getContent())
        .noneMatch(
            recipe ->
                java.util.List.of(
                        "Photo gallery",
                        "Personal links page",
                        "Morning news digest",
                        "Website uptime monitor")
                    .contains(recipe.title()));
    assertThat(publicRecipes.getContent())
        .filteredOn(recipe -> recipe.title().equals("Telegram bot for your workspace"))
        .hasSize(1);
    for (var entry : SOURCES.entrySet()) {
      var id = UUID.fromString("00000000-0000-4000-8000-00000000" + entry.getValue());
      var source = Files.readString(Path.of("../../docs/recipes", entry.getKey() + ".md"));
      var headerEnd = source.indexOf("\n---", 3);
      var body = source.substring(headerEnd + 4).strip();
      var recipe = recipes.get(id, PUBLISHER);
      assertThat(recipe.content()).as(entry.getKey()).isEqualTo(body);
      boolean isPrivate = id.equals(CIELA);
      assertThat(recipe.visibility()).isEqualTo(isPrivate ? Visibility.PRIVATE : Visibility.PUBLIC);
      assertThat(recipe.author().firstName()).isEqualTo(isPrivate ? "Pavel" : "workspaced");
      assertThat(recipe.author().lastName()).isEqualTo(isPrivate ? "Usanli" : "");
      if (isPrivate) {
        assertThat(publicRecipes.getContent()).noneMatch(item -> item.id().equals(id));
      } else {
        assertThat(publicRecipes.getContent()).anyMatch(item -> item.id().equals(id));
      }
    }
  }

  @Test
  @Transactional
  void privateCielaIsClaimedAndPublicBrowserRemainsOwnedByWorkspaced() {
    var pavel = users.saveAndFlush(TestUsers.newUser("pavel.usanli@gmail.com"));
    var other = users.saveAndFlush(TestUsers.newUser("other@example.com"));

    starterRecipes.claimPublisher(other.getId(), "other@example.com");
    assertThat(recipes.get(CIELA, PUBLISHER).mine()).isTrue();
    assertThat(recipes.get(BROWSER, PUBLISHER).mine()).isTrue();
    starterRecipes.claimPublisher(pavel.getId(), "Pavel.Usanli@gmail.com");

    var wordpress =
        recipes.get(UUID.fromString("00000000-0000-4000-8000-00000000b001"), pavel.getId());
    assertThat(wordpress.mine()).isFalse();
    assertThat(wordpress.author().firstName()).isEqualTo("workspaced");
    var browser = recipes.get(BROWSER, pavel.getId());
    assertThat(browser.mine()).isFalse();
    assertThat(browser.visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(browser.author().firstName()).isEqualTo("workspaced");
    assertThat(browser.author().lastName()).isEmpty();
    assertThat(recipes.get(BROWSER, other.getId()).visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(recipes.list(pavel.getId(), "private", "", null, "newest", 0, 100).getContent())
        .noneMatch(recipe -> recipe.id().equals(BROWSER));
    assertThat(recipes.list(other.getId(), "public", "", null, "newest", 0, 100).getContent())
        .anyMatch(recipe -> recipe.id().equals(BROWSER));
    assertThat(recipes.get(CIELA, pavel.getId()).mine()).isTrue();
    assertThat(recipes.get(CIELA, pavel.getId()).visibility()).isEqualTo(Visibility.PRIVATE);
    assertThat(recipes.list(pavel.getId(), "private", "", null, "newest", 0, 100).getContent())
        .anyMatch(recipe -> recipe.id().equals(CIELA));
    assertThat(recipes.list(pavel.getId(), "public", "", null, "newest", 0, 100).getContent())
        .noneMatch(recipe -> recipe.id().equals(CIELA));
    assertThat(recipes.list(other.getId(), "private", "", null, "newest", 0, 100).getContent())
        .noneMatch(recipe -> recipe.id().equals(CIELA));
    assertThatThrownBy(() -> recipes.get(CIELA, other.getId()))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
