package io.github.kalpak44.workspacedai.api.variable;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link VariablesApi}. */
@RestController
public class VariablesController implements VariablesApi {

  private final VariableService service;

  public VariablesController(VariableService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<Variable>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<List<Variable>> save(UUID workspaceId, List<Variable> variables) {
    return ResponseEntity.ok(service.save(workspaceId, variables));
  }
}
