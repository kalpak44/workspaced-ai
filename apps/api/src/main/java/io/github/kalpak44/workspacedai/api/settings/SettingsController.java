package io.github.kalpak44.workspacedai.api.settings;

import io.github.kalpak44.workspacedai.api.harness.ModelCatalogue;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link SettingsApi}. */
@RestController
public class SettingsController implements SettingsApi {

  private final SettingsService service;

  public SettingsController(SettingsService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<SessionTtlSettings> sessionTtl(UUID workspaceId) {
    return ResponseEntity.ok(service.sessionTtl(workspaceId));
  }

  @Override
  public ResponseEntity<SessionTtlSettings> setSessionTtl(
      UUID workspaceId, SessionTtlSettings request) {
    return ResponseEntity.ok(service.setSessionTtl(workspaceId, request));
  }

  @Override
  public ResponseEntity<ModelCatalogue> models(UUID workspaceId) {
    return ResponseEntity.ok(service.models(workspaceId));
  }

  @Override
  public ResponseEntity<ModelRequest> setModel(UUID workspaceId, ModelRequest request) {
    return ResponseEntity.ok(new ModelRequest(service.setModel(workspaceId, request.model())));
  }

  @Override
  public ResponseEntity<List<McpServer>> mcpServers(UUID workspaceId) {
    return ResponseEntity.ok(service.mcpServers(workspaceId));
  }

  @Override
  public ResponseEntity<McpServer> addMcpServer(UUID workspaceId, McpServerRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.addMcpServer(workspaceId, request));
  }

  @Override
  public ResponseEntity<Void> removeMcpServer(UUID workspaceId, String name) {
    service.removeMcpServer(workspaceId, name);
    return ResponseEntity.noContent().build();
  }
}
