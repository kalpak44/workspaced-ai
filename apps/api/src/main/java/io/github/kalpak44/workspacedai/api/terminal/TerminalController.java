package io.github.kalpak44.workspacedai.api.terminal;

import io.github.kalpak44.workspacedai.api.harness.TerminalSession;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TerminalController implements TerminalApi {
  private final TerminalService service;

  public TerminalController(TerminalService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<TerminalSession>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<TerminalSession> create(UUID workspaceId, CreateTerminalRequest request) {
    var terminal = service.create(workspaceId, request.title());
    return ResponseEntity.created(
            URI.create("/api/v1/workspaces/" + workspaceId + "/terminals/" + terminal.id()))
        .body(terminal);
  }

  @Override
  public ResponseEntity<TerminalSession> get(UUID workspaceId, String terminalId) {
    return ResponseEntity.ok(service.get(workspaceId, terminalId));
  }

  @Override
  public ResponseEntity<Void> delete(UUID workspaceId, String terminalId) {
    service.delete(workspaceId, terminalId);
    return ResponseEntity.noContent().build();
  }
}
