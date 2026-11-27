package io.github.kalpak44.workspacedai.api.collaborator;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link CollaboratorsApi}. */
@RestController
public class CollaboratorsController implements CollaboratorsApi {

  private final CollaboratorService service;

  public CollaboratorsController(CollaboratorService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<Collaborator>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<Collaborator> invite(UUID workspaceId, InviteRequest request) {
    var invitation =
        service.invite(
            workspaceId, CurrentUser.id(), CurrentUser.profile().email(), request.email());
    return ResponseEntity.status(HttpStatus.CREATED).body(invitation);
  }

  @Override
  public ResponseEntity<Void> revoke(UUID workspaceId, UUID invitationId) {
    service.revoke(workspaceId, invitationId, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> remove(UUID workspaceId, UUID userId) {
    service.remove(workspaceId, userId, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<AcceptedInvitation> accept(AcceptInvitationRequest request) {
    return ResponseEntity.ok(
        service.accept(CurrentUser.id(), CurrentUser.profile().email(), request.code()));
  }
}
