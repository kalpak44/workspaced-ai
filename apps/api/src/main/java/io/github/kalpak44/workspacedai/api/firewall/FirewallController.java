package io.github.kalpak44.workspacedai.api.firewall;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link FirewallApi}. */
@RestController
public class FirewallController implements FirewallApi {

  private final FirewallService service;

  public FirewallController(FirewallService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<WorkspaceFirewall> get(UUID workspaceId) {
    return ResponseEntity.ok(service.get(workspaceId));
  }

  @Override
  public ResponseEntity<WorkspaceFirewall> set(UUID workspaceId, FirewallRulesRequest request) {
    return ResponseEntity.ok(service.set(workspaceId, request.rules()));
  }

  @Override
  public ResponseEntity<Void> setAgentAccess(UUID workspaceId, AgentAccessRequest request) {
    service.setAgentAllowed(workspaceId, request.allowed());
    return ResponseEntity.noContent().build();
  }
}
