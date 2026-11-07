package io.github.kalpak44.workspacedai.api.task;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link TasksApi}. */
@RestController
public class TasksController implements TasksApi {

  private final TaskService service;

  public TasksController(TaskService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<TaskFile>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<TaskFile> save(UUID workspaceId, String name, TaskRequest request) {
    return ResponseEntity.ok(service.save(workspaceId, name, request));
  }

  @Override
  public ResponseEntity<Void> delete(UUID workspaceId, String name) {
    service.delete(workspaceId, name);
    return ResponseEntity.noContent().build();
  }
}
