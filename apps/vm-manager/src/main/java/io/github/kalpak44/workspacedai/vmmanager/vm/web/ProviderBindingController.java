package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal transport only; credentials are never returned in receipts or exception messages. */
@RestController
@RequestMapping("/vms/{id}")
public class ProviderBindingController {
  public record Request(
      @Min(1) long revision, @NotBlank String provider, @NotBlank @Size(max = 1000) String key) {
    @Override
    public String toString() {
      return "ProviderBinding[revision=" + revision + ", provider=" + provider + "]";
    }
  }

  public record Ready(@NotNull @Min(1) Long revision) {}

  private final ProviderBindingService bindings;
  private final VmService vms;

  public ProviderBindingController(ProviderBindingService bindings, VmService vms) {
    this.bindings = bindings;
    this.vms = vms;
  }

  @GetMapping("/provider-binding")
  public ProviderBindingService.Receipt get(@PathVariable UUID id) {
    return bindings.receipt(id);
  }

  @PutMapping("/provider-binding")
  public ResponseEntity<Void> put(@PathVariable UUID id, @Valid @RequestBody Request body) {
    if (vms.get(id).status() == VmStatus.STOPPED)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "The VM is stopped");
    bindings.request(id, body.revision(), body.provider(), body.key());
    if (!Objects.equals(bindings.receipt(id).readyRevision(), body.revision())) vms.restart(id);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/ready-binding")
  public ResponseEntity<?> ready(@PathVariable UUID id, @Valid @RequestBody Ready body) {
    // A stale report is acknowledged but never promotes the VM or provider revision.
    return ResponseEntity.ok(bindings.ready(id, body.revision()) ? vms.ready(id) : vms.get(id));
  }
}
