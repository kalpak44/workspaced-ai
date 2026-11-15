package io.github.kalpak44.workspacedai.api.compute;

import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Routes and documentation are declared on {@link WorkspaceMetricsApi}. */
@RestController
public class WorkspaceMetricsController implements WorkspaceMetricsApi {

  private final VmManager vmManager;
  private final WorkspaceRepository workspaces;

  public WorkspaceMetricsController(VmManager vmManager, WorkspaceRepository workspaces) {
    this.vmManager = vmManager;
    this.workspaces = workspaces;
  }

  @Override
  public ResponseEntity<WorkspaceMetrics> get(UUID workspaceId, String range) {
    if (!workspaces.existsById(workspaceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId);
    }
    try {
      return ResponseEntity.ok(vmManager.metricsFor(workspaceId, range));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace has no VM yet", e);
    }
  }
}
