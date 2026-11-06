package io.github.kalpak44.workspacedai.api.instructions;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
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
  public ResponseEntity<GeneralInstructions> general() {
    return ResponseEntity.ok(new GeneralInstructions(service.general(CurrentUser.id())));
  }

  @Override
  public ResponseEntity<GeneralInstructions> setGeneral(InstructionsRequest request) {
    return ResponseEntity.ok(
        new GeneralInstructions(service.setGeneral(CurrentUser.id(), request.instructions())));
  }

  @Override
  public ResponseEntity<WorkspaceInstructions> workspace(UUID workspaceId) {
    return ResponseEntity.ok(service.workspace(workspaceId));
  }

  @Override
  public ResponseEntity<SavedInstructions> setWorkspace(
      UUID workspaceId, InstructionsRequest request) {
    return ResponseEntity.ok(service.setWorkspace(workspaceId, request.instructions()));
  }
}
