package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link WorkspaceApi}. */
@RestController
public class WorkspaceController implements WorkspaceApi {

  private final WorkspaceService service;
  private final WorkspaceEvents events;

  public WorkspaceController(WorkspaceService service, WorkspaceEvents events) {
    this.service = service;
    this.events = events;
  }

  @Override
  public ResponseEntity<SseEmitter> events() {
    return ResponseEntity.ok(events.subscribe(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<List<Workspace>> list() {
    return ResponseEntity.ok(service.list(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<Workspace> create(CreateWorkspaceRequest request) {
    var provider =
        ModelProvider.byId(request.modelProvider())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "unknown model provider " + request.modelProvider()));
    var workspace =
        service.create(
            CurrentUser.id(),
            request.trimmedName(),
            provider,
            request.trimmedApiKeyOrNull(),
            request.serverTypeOrNull());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(workspace.id())
            .toUri();
    return ResponseEntity.created(location).body(workspace);
  }

  @Override
  public ResponseEntity<Workspace> rename(UUID id, WorkspaceRequest request) {
    return service
        .rename(id, request.trimmedName())
        .map(ResponseEntity::ok)
        .orElseThrow(() -> notFound(id));
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    if (!service.delete(id)) {
      throw notFound(id);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> restart(UUID id) {
    if (!service.restart(id)) {
      throw notFound(id);
    }
    return ResponseEntity.accepted().build();
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id);
  }
}
