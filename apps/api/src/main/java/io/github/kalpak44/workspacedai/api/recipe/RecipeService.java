package io.github.kalpak44.workspacedai.api.recipe;

import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The recipe store: Markdown instructions users write, share and rate, and apply to their own
 * workspaces. A public recipe is seen by everyone, a private one by its author alone, and one the
 * caller may not see answers as one that does not exist.
 */
@Service
public class RecipeService {

  static final int MAX_PAGE_SIZE = 100;

  private final RecipeStore store;

  public RecipeService(RecipeStore store) {
    this.store = store;
  }

  /** The caller's own private recipes, or everyone's public ones, the caller's included. */
  @Transactional(readOnly = true)
  public Page<RecipeSummary> list(
      UUID viewer, String scope, String query, String category, String sort, int page, int size) {
    var publicScope =
        switch (scope.toLowerCase(Locale.ROOT)) {
          case "public" -> true;
          case "private" -> false;
          default -> throw invalid("the scope is public or private");
        };
    var order =
        switch (sort.toLowerCase(Locale.ROOT)) {
          case "top" -> RecipeStore.Order.TOP;
          case "newest" -> RecipeStore.Order.NEWEST;
          case "updated" -> RecipeStore.Order.UPDATED;
          default -> throw invalid("the sort is top, newest or updated");
        };
    if (category != null && !category.isBlank() && !RecipeRequest.CATEGORIES.contains(category)) {
      throw invalid("no category " + category);
    }
    if (page < 0 || size < 1) {
      throw invalid("the page is 0 or more and its size 1 or more");
    }
    var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    var filter =
        new RecipeStore.Filter(
            publicScope,
            viewer,
            query == null || query.isBlank() ? null : query.trim(),
            category == null || category.isBlank() ? null : category);
    var recipes =
        store.list(filter, order, pageable.getPageSize(), pageable.getOffset()).stream()
            .map(Recipe::toSummary)
            .toList();
    return new PageImpl<>(recipes, pageable, store.count(filter));
  }

  public Recipe get(UUID id, UUID viewer) {
    return visible(id, viewer);
  }

  @Transactional
  public Recipe create(UUID author, RecipeRequest request) {
    var id = store.create(author, request.normalized());
    return visible(id, author);
  }

  /** Only by its author; a public recipe someone else wrote is forbidden rather than hidden. */
  @Transactional
  public Recipe update(UUID id, UUID viewer, RecipeRequest request) {
    var fields = request.normalized();
    authored(visible(id, viewer), "only its author can change a recipe");
    store.update(id, fields);
    return visible(id, viewer);
  }

  /** By its author, or by an admin removing a public recipe. */
  @Transactional
  public void delete(UUID id, UUID viewer, boolean admin) {
    var recipe = visible(id, viewer);
    if (!admin) {
      authored(recipe, "only its author or an admin can delete a recipe");
    }
    store.delete(id);
  }

  /** Not one's own: an author rating their recipe would only inflate it. */
  @Transactional
  public RatingResult rate(UUID id, UUID viewer, int stars) {
    if (visible(id, viewer).mine()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "you cannot rate your own recipe");
    }
    store.rate(id, viewer, stars);
    var rated = visible(id, viewer);
    return new RatingResult(rated.rating().average(), rated.rating().count(), rated.myRating());
  }

  @Transactional
  public void unrate(UUID id, UUID viewer) {
    visible(id, viewer);
    store.unrate(id, viewer);
  }

  private Recipe visible(UUID id, UUID viewer) {
    return store
        .find(id, viewer)
        .filter(recipe -> recipe.visibility() == Visibility.PUBLIC || recipe.mine())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no recipe " + id));
  }

  private static void authored(Recipe recipe, String reason) {
    if (!recipe.mine()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
  }

  private static ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
