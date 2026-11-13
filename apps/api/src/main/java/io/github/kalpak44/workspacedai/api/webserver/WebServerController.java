package io.github.kalpak44.workspacedai.api.webserver;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link WebServerApi}. */
@RestController
public class WebServerController implements WebServerApi {

  private final WebServerService service;

  public WebServerController(WebServerService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<WebServer> get(UUID workspaceId) {
    return ResponseEntity.ok(service.get(workspaceId));
  }

  @Override
  public ResponseEntity<WebServer> enable(UUID workspaceId, WebServerRequest request) {
    return ResponseEntity.ok(service.enable(workspaceId, request.name()));
  }

  @Override
  public ResponseEntity<WebServer> setAgentAllowed(UUID workspaceId, AgentAccessRequest request) {
    return ResponseEntity.ok(service.setAgentAllowed(workspaceId, request.allowed()));
  }

  @Override
  public ResponseEntity<Void> disable(UUID workspaceId) {
    service.disable(workspaceId);
    return ResponseEntity.noContent().build();
  }
}
