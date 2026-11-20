package io.github.kalpak44.workspacedai.api.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A separate database context keeps the catalogue intact while CRUD tests delete their fixtures.
 */
@SpringBootTest(properties = "workspaced.test.starter-catalogue=true")
@Import({TestDatabase.class, TestRuntime.class})
class StarterRecipesTest {
  private static final Map<String, String> SOURCES =
      Map.of(
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
          "b00b");

  @Autowired RecipeService recipes;

  @Test
  void publishesEverySourceRecipeWithItsCodeUnchanged() throws IOException {
    var viewer = UUID.randomUUID();
    var publicRecipes = recipes.list(viewer, "public", "", null, "newest", 0, 100);
    for (var entry : SOURCES.entrySet()) {
      var id = UUID.fromString("00000000-0000-4000-8000-00000000" + entry.getValue());
      var source = Files.readString(Path.of("../../docs/recipes", entry.getKey() + ".md"));
      var headerEnd = source.indexOf("\n---", 3);
      var body = source.substring(headerEnd + 4).strip();
      var recipe = recipes.get(id, viewer);
      assertThat(recipe.content()).as(entry.getKey()).isEqualTo(body);
      assertThat(recipe.visibility()).isEqualTo(Visibility.PUBLIC);
      assertThat(recipe.author().firstName()).isEqualTo("Pavel");
      assertThat(recipe.author().lastName()).isEqualTo("Usanli");
      assertThat(publicRecipes.getContent()).anyMatch(item -> item.id().equals(id));
    }
  }
}
