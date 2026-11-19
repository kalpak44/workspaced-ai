package io.github.kalpak44.workspacedai.api.recipe;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Puts names on recipes' authors. The names live in Auth0, so this runs once the recipe service's
 * transaction has closed. An author Auth0 has no name for, or does not answer for, is Unknown.
 */
@Component
public class RecipeAuthors {

  static final String UNKNOWN = "Unknown";

  private final UserRepository users;
  private final Auth0Users auth0Users;

  public RecipeAuthors(UserRepository users, Auth0Users auth0Users) {
    this.users = users;
    this.auth0Users = auth0Users;
  }

  public Recipe named(Recipe recipe) {
    return recipe.withAuthor(authors(List.of(recipe.author().id())).get(recipe.author().id()));
  }

  /** One lookup for the whole page, however many authors it has. */
  public Page<RecipeSummary> named(Page<RecipeSummary> page) {
    var authors = authors(page.map(s -> s.author().id()).toSet());
    return page.map(summary -> summary.withAuthor(authors.get(summary.author().id())));
  }

  private Map<UUID, RecipeAuthor> authors(Collection<UUID> ids) {
    var subjects = new HashMap<UUID, String>();
    users.findAllById(ids).forEach(u -> subjects.put(u.getId(), u.getIdpSubject()));
    var profiles = auth0Users.profiles(subjects.values());
    var authors = new HashMap<UUID, RecipeAuthor>();
    for (var id : ids) {
      var profile = profiles.get(subjects.get(id));
      authors.put(id, author(id, profile));
    }
    return authors;
  }

  private static RecipeAuthor author(UUID id, Auth0Users.Profile profile) {
    if (profile == null || (profile.givenName().isBlank() && profile.familyName().isBlank())) {
      return new RecipeAuthor(id, UNKNOWN, "");
    }
    return new RecipeAuthor(id, profile.givenName(), profile.familyName());
  }
}
