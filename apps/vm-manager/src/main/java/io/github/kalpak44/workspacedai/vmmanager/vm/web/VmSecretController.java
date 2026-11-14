package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.VmSecret;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmSecretService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmSecretDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmSecretDto;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Routes, validation and documentation are declared on {@link VmSecretApi}; errors are answered by
 * {@link ApiExceptionHandler}.
 */
@RestController
public class VmSecretController implements VmSecretApi {

  private final VmSecretService service;

  public VmSecretController(VmSecretService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<VmSecretDto>> list(UUID id) {
    return ResponseEntity.ok(service.list(id).stream().map(VmSecretController::toDto).toList());
  }

  @Override
  public ResponseEntity<VmSecretDto> add(UUID id, CreateVmSecretDto body) {
    var secret = service.add(id, body.name(), body.value());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{name}")
            .buildAndExpand(secret.name())
            .toUri();
    return ResponseEntity.created(location).body(toDto(secret));
  }

  @Override
  public ResponseEntity<Void> remove(UUID id, String name) {
    service.remove(id, name);
    return ResponseEntity.noContent().build();
  }

  private static VmSecretDto toDto(VmSecret secret) {
    return new VmSecretDto(secret.name(), secret.system());
  }
}
