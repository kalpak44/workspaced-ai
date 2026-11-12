package io.github.kalpak44.workspacedai.api.backup;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import io.github.kalpak44.workspacedai.api.workspace.Workspace;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link BackupApi}. */
@RestController
public class BackupController implements BackupApi {

  private final BackupService service;

  public BackupController(BackupService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Backup> start(UUID id) {
    var backup = service.start(id);
    return ResponseEntity.accepted().location(backupUri(backup.id())).body(backup);
  }

  @Override
  public ResponseEntity<List<Backup>> list() {
    return ResponseEntity.ok(service.list(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<Backup> get(UUID id) {
    return ResponseEntity.ok(service.get(CurrentUser.id(), id));
  }

  @Override
  public ResponseEntity<BackupDownload> download(UUID id) {
    return ResponseEntity.ok(service.download(CurrentUser.id(), id));
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    service.delete(CurrentUser.id(), id);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> restore(UUID id) {
    service.restore(CurrentUser.id(), id);
    return ResponseEntity.accepted().build();
  }

  @Override
  public ResponseEntity<Workspace> createWorkspace(UUID id, CloneBackupRequest request) {
    var workspace =
        service.createFrom(CurrentUser.id(), id, request.trimmedName(), request.trimmedApiKey());
    var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/workspaces/{id}")
            .buildAndExpand(workspace.id())
            .toUri();
    return ResponseEntity.created(location).body(workspace);
  }

  private static URI backupUri(UUID id) {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/backups/{id}")
        .buildAndExpand(id)
        .toUri();
  }
}
