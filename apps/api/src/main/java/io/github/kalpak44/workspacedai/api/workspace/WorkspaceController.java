package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/workspaces")
public class WorkspaceController {

  private final WorkspaceRepository repository;

  public WorkspaceController(WorkspaceRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public List<Workspace> list() {
    return repository.findAll();
  }

  @PostMapping
  public ResponseEntity<Workspace> create(@Valid @RequestBody WorkspaceRequest request) {
    var workspace = repository.create(request.trimmedName());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(workspace.id())
            .toUri();
    return ResponseEntity.created(location).body(workspace);
  }

  @PutMapping("/{id}")
  public Workspace rename(@PathVariable UUID id, @Valid @RequestBody WorkspaceRequest request) {
    return repository.rename(id, request.trimmedName()).orElseThrow(() -> notFound(id));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    if (!repository.delete(id)) {
      throw notFound(id);
    }
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id);
  }
}
