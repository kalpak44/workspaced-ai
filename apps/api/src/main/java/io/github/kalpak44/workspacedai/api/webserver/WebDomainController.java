package io.github.kalpak44.workspacedai.api.webserver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/workspaces/{workspaceId}/web-server/domains")
public class WebDomainController {
  private final WebServerService service;

  public WebDomainController(WebServerService service) {
    this.service = service;
  }

  public record DomainRequest(@NotNull Boolean enabled) {}

  @GetMapping
  public List<WebServerService.Domain> list(@PathVariable UUID workspaceId) {
    return service.domains(workspaceId);
  }

  @PutMapping("/{name}")
  public List<WebServerService.Domain> save(
      @PathVariable UUID workspaceId,
      @PathVariable @Pattern(regexp = WebServerService.NAME) String name,
      @Valid @RequestBody DomainRequest request) {
    return service.saveDomain(workspaceId, name, request.enabled());
  }

  @DeleteMapping("/{name}")
  public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable String name) {
    service.removeDomain(workspaceId, name);
    return ResponseEntity.noContent().build();
  }
}
