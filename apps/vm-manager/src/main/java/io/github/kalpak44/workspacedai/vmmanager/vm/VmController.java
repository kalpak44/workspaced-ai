package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link VmApi}. */
@RestController
public class VmController implements VmApi {

  private final VmService service;
  private final VmEvents events;

  public VmController(VmService service, VmEvents events) {
    this.service = service;
    this.events = events;
  }

  @Override
  public ResponseEntity<List<Vm>> list(Optional<String> ownerRef) {
    return ResponseEntity.ok(service.list(ownerRef));
  }

  @Override
  public ResponseEntity<SseEmitter> events() {
    return ResponseEntity.ok(events.subscribe(service.list(Optional.empty())));
  }

  @Override
  public ResponseEntity<Vm> get(UUID id) {
    return service.get(id).map(ResponseEntity::ok).orElseThrow(() -> notFound(id));
  }

  @Override
  public ResponseEntity<Vm> create(CreateVmRequest request) {
    var created = service.create(request);
    if (!created.isNew()) {
      return ResponseEntity.ok(created.vm());
    }
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.vm().id())
            .toUri();
    return ResponseEntity.created(location).body(created.vm());
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    if (!service.delete(id)) {
      throw notFound(id);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Vm> restart(UUID id) {
    return service
        .restart(id)
        .map(vm -> ResponseEntity.accepted().body(vm))
        .orElseThrow(() -> notFound(id));
  }

  @Override
  public ResponseEntity<Vm> ready(UUID id) {
    return service.ready(id).map(ResponseEntity::ok).orElseThrow(() -> notFound(id));
  }

  @Override
  public ResponseEntity<Void> heartbeat(UUID id) {
    service.heartbeat(id).orElseThrow(() -> notFound(id));
    return ResponseEntity.noContent().build();
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no VM " + id);
  }
}
