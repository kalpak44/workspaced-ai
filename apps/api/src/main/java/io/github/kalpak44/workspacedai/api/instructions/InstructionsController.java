package io.github.kalpak44.workspacedai.api.instructions;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link InstructionsApi}. */
@RestController
public class InstructionsController implements InstructionsApi {

  private final InstructionsService service;

  public InstructionsController(InstructionsService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<InstructionFile>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<InstructionFile> save(
      UUID workspaceId, String name, InstructionsRequest request) {
    return ResponseEntity.ok(service.save(workspaceId, name, request.content()));
  }

  @Override
  public ResponseEntity<Void> delete(UUID workspaceId, String name) {
    service.delete(workspaceId, name);
    return ResponseEntity.noContent().build();
  }
}
