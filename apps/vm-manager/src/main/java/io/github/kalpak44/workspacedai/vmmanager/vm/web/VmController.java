package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.HarnessService;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.HarnessDto;
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
  private final HarnessService harnesses;
  private final VmEventStream events;
  private final VmDtoMapper mapper;

  public VmController(
      VmService service, HarnessService harnesses, VmEventStream events, VmDtoMapper mapper) {
    this.service = service;
    this.harnesses = harnesses;
    this.events = events;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<List<VmDto>> list(Optional<UUID> workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId).stream().map(mapper::toDto).toList());
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
    var created =
        service.create(
            body.workspaceId(),
            body.userId(),
            body.serverType(),
            body.openRouterApiKey(),
            body.environment());
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
  public ResponseEntity<VmDto> heartbeat(UUID id) {
    return ResponseEntity.ok(mapper.toDto(service.heartbeat(id)));
  }

  @Override
  public ResponseEntity<HarnessDto> harness(UUID id) {
    var harness = harnesses.get(id);
    return ResponseEntity.ok(new HarnessDto(harness.image(), harness.environment()));
  }
}
