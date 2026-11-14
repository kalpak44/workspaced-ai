package io.github.kalpak44.workspacedai.api.recipe;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link RecipeDraftsApi}. */
@RestController
public class RecipeDraftsController implements RecipeDraftsApi {

  private final RecipeDraftsService service;

  public RecipeDraftsController(RecipeDraftsService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<RecipeDraft>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<RecipeDraft> save(
      UUID workspaceId, String name, RecipeDraftRequest request) {
    return ResponseEntity.ok(service.save(workspaceId, name, request.content()));
  }

  @Override
  public ResponseEntity<Void> delete(UUID workspaceId, String name) {
    service.delete(workspaceId, name);
    return ResponseEntity.noContent().build();
  }
}
