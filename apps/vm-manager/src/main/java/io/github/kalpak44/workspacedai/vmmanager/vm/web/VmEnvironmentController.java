package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.VmSecretService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmSecretDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.SystemVariableDto;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Routes, validation and documentation are declared on {@link VmEnvironmentApi}; errors are
 * answered by {@link ApiExceptionHandler}.
 */
@RestController
public class VmEnvironmentController implements VmEnvironmentApi {

  private final VmSecretService service;

  public VmEnvironmentController(VmSecretService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Void> set(UUID id, String name, SystemVariableDto body) {
    service.setSystemVariable(id, variable(name), body.value());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> remove(UUID id, String name) {
    service.setSystemVariable(id, variable(name), null);
    return ResponseEntity.noContent().build();
  }

  // It becomes an environment variable on the VM, so it has to be a valid name.
  private static String variable(String name) {
    if (!name.matches(CreateVmSecretDto.NAME_PATTERN)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is not a variable name");
    }
    return name;
  }
}
