package io.github.kalpak44.workspacedai.api.secret;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link SecretApi}. */
@RestController
public class SecretController implements SecretApi {

  private final SecretService service;

  public SecretController(SecretService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<Secret>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<Secret> add(UUID workspaceId, SecretRequest request) {
    var secret = service.add(workspaceId, request.name(), request.value());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{name}")
            .buildAndExpand(secret.name())
            .toUri();
    return ResponseEntity.created(location).body(secret);
  }

  @Override
  public ResponseEntity<Void> remove(UUID workspaceId, String name) {
    service.remove(workspaceId, name);
    return ResponseEntity.noContent().build();
  }
}
