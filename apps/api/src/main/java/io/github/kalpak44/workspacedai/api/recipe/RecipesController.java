package io.github.kalpak44.workspacedai.api.recipe;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import java.util.UUID;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link RecipesApi}. */
@RestController
public class RecipesController implements RecipesApi {

  private final RecipeService service;

  public RecipesController(RecipeService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<PagedModel<RecipeSummary>> list(
      String scope, String q, String category, String sort, int page, int size) {
    return ResponseEntity.ok(
        new PagedModel<>(service.list(CurrentUser.id(), scope, q, category, sort, page, size)));
  }

  @Override
  public ResponseEntity<Recipe> get(UUID id) {
    return ResponseEntity.ok(service.get(id, CurrentUser.id()));
  }

  @Override
  public ResponseEntity<Recipe> create(RecipeRequest request) {
    var recipe = service.create(CurrentUser.id(), request);
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(recipe.id())
            .toUri();
    return ResponseEntity.created(location).body(recipe);
  }

  @Override
  public ResponseEntity<Recipe> update(UUID id, RecipeRequest request) {
    return ResponseEntity.ok(service.update(id, CurrentUser.id(), request));
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    service.delete(id, CurrentUser.id(), CurrentUser.admin());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<RatingResult> rate(UUID id, RatingRequest request) {
    return ResponseEntity.ok(service.rate(id, CurrentUser.id(), request.stars()));
  }

  @Override
  public ResponseEntity<Void> unrate(UUID id) {
    service.unrate(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
