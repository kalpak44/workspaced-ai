package io.github.kalpak44.workspacedai.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiConnectorController {
  public record Create(
      @NotBlank @Size(max = 100) String name,
      @NotBlank String provider,
      @NotBlank @Size(max = 1000) String apiKey) {}

  public record Selection(@NotNull UUID connectorId) {}

  private final AiConnectorService service;

  public AiConnectorController(AiConnectorService service) {
    this.service = service;
  }

  @GetMapping("/ai-connectors")
  public List<AiConnectorService.Connector> list() {
    return service.list();
  }

  @PostMapping("/ai-connectors")
  @ResponseStatus(HttpStatus.CREATED)
  public AiConnectorService.Connector create(@Valid @RequestBody Create request) {
    return service.create(request.name().strip(), request.provider(), request.apiKey().strip());
  }

  @DeleteMapping("/ai-connectors/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  @GetMapping("/workspaces/{id}/ai-connector")
  public ResponseEntity<AiConnectorService.Connector> selected(@PathVariable UUID id) {
    var selected = service.selected(id);
    return selected == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(selected);
  }

  @PutMapping("/workspaces/{id}/ai-connector")
  public AiConnectorService.Connector select(
      @PathVariable UUID id, @Valid @RequestBody Selection request) {
    return service.select(id, request.connectorId());
  }
}
