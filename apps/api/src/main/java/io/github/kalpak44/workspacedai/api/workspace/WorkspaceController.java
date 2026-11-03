package io.github.kalpak44.workspacedai.api.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link WorkspaceApi}. */
@RestController
public class WorkspaceController implements WorkspaceApi {

  private final WorkspaceRepository repository;

  public WorkspaceController(WorkspaceRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<Workspace> list() {
    return repository.findAll();
  }

  @Override
  public ResponseEntity<Workspace> create(WorkspaceRequest request) {
    var workspace = repository.create(request.trimmedName());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(workspace.id())
            .toUri();
    return ResponseEntity.created(location).body(workspace);
  }

  @Override
  public Workspace rename(UUID id, WorkspaceRequest request) {
    return repository.rename(id, request.trimmedName()).orElseThrow(() -> notFound(id));
  }

  @Override
  public void delete(UUID id) {
    if (!repository.delete(id)) {
      throw notFound(id);
    }
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id);
  }
}
