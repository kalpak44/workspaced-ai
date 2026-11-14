package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.VmService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDtoMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Routes, validation and documentation are declared on {@link VmApi}; errors are answered by {@link
 * ApiExceptionHandler}, so each method only maps between DTOs and the service.
 */
@RestController
public class VmController implements VmApi {

  private final VmService service;
  private final VmEventStream events;
  private final VmDtoMapper mapper;

  public VmController(VmService service, VmEventStream events, VmDtoMapper mapper) {
    this.service = service;
    this.events = events;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<List<VmDto>> list(Optional<String> ownerRef) {
    return ResponseEntity.ok(service.list(ownerRef).stream().map(mapper::toDto).toList());
  }

  @Override
  public ResponseEntity<SseEmitter> events() {
    return ResponseEntity.ok(events.subscribe());
  }

  @Override
  public ResponseEntity<VmDto> get(UUID id) {
    return ResponseEntity.ok(mapper.toDto(service.get(id)));
  }

  @Override
  public ResponseEntity<VmDto> create(CreateVmDto body) {
    var created = service.create(body.ownerRef(), body.serverType());
    var vm = mapper.toDto(created.vm());
    if (!created.isNew()) {
      return ResponseEntity.ok(vm);
    }
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(vm.id());
    return ResponseEntity.created(location.toUri()).body(vm);
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<VmDto> restart(UUID id) {
    return ResponseEntity.accepted().body(mapper.toDto(service.restart(id)));
  }

  @Override
  public ResponseEntity<VmDto> ready(UUID id) {
    return ResponseEntity.ok(mapper.toDto(service.ready(id)));
  }

  @Override
  public ResponseEntity<Void> heartbeat(UUID id) {
    service.heartbeat(id);
    return ResponseEntity.noContent().build();
  }
}
