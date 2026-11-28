package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.ComputeProvider;
import io.github.kalpak44.workspacedai.vmmanager.vm.HarnessService;
import io.github.kalpak44.workspacedai.vmmanager.vm.MetricsRange;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmFirewallService;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmMetricsService;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.FirewallRuleDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.HarnessDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.HeartbeatAnswerDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.HeartbeatDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDtoMapper;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmMetricsDto;
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
  private final io.github.kalpak44.workspacedai.vmmanager.vm.ProviderBindingService
      providerBindings;

  private final io.github.kalpak44.workspacedai.vmmanager.vm.SupervisorProperties supervisor;

  private final io.github.kalpak44.workspacedai.vmmanager.vm.SupervisorFleet supervisorFleet;

  private final VmService service;
  private final HarnessService harnesses;
  private final VmMetricsService metrics;
  private final VmEventStream events;
  private final VmDtoMapper mapper;
  private final VmFirewallService firewalls;

  public VmController(
      VmService service,
      HarnessService harnesses,
      VmMetricsService metrics,
      VmEventStream events,
      VmDtoMapper mapper,
      VmFirewallService firewalls,
      io.github.kalpak44.workspacedai.vmmanager.vm.ProviderBindingService providerBindings,
      io.github.kalpak44.workspacedai.vmmanager.vm.SupervisorProperties supervisor,
      io.github.kalpak44.workspacedai.vmmanager.vm.SupervisorFleet supervisorFleet) {
    this.supervisor = supervisor;
    this.supervisorFleet = supervisorFleet;
    this.providerBindings = providerBindings;
    this.firewalls = firewalls;
    this.service = service;
    this.harnesses = harnesses;
    this.metrics = metrics;
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
    // Refuses a cloud it cannot create in; VmService creates every VM at Hetzner.
    ComputeProvider.resolve(body.provider());
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
  public ResponseEntity<VmDto> stop(UUID id) {
    return ResponseEntity.accepted().body(mapper.toDto(service.stop(id)));
  }

  @Override
  public ResponseEntity<VmDto> start(UUID id) {
    return ResponseEntity.accepted().body(mapper.toDto(service.start(id)));
  }

  @Override
  public ResponseEntity<VmDto> ready(UUID id) {
    return ResponseEntity.ok(
        mapper.toDto(
            providerBindings.receipt(id).revision() == null ? service.ready(id) : service.get(id)));
  }

  @Override
  public ResponseEntity<HeartbeatAnswerDto> heartbeat(UUID id, HeartbeatDto body) {
    var vm = service.heartbeat(id);
    if (body != null) supervisorFleet.report(id, body.supervisor());
    if (body != null && body.memory() != null) {
      metrics.report(id, body.memory().totalBytes(), body.memory().availableBytes());
    }
    var rules =
        firewalls
            .hostRules(id)
            .map(
                list ->
                    list.stream()
                        .map(
                            r ->
                                new FirewallRuleDto(
                                    r.protocol(), r.port(), r.sourceIps(), r.description()))
                        .toList())
            .orElse(null);
    var binding = providerBindings.receipt(id);
    return ResponseEntity.ok(
        new HeartbeatAnswerDto(
            vm.status() != io.github.kalpak44.workspacedai.vmmanager.vm.VmStatus.STOPPED
                    && binding.revision() != null
                    && !java.util.Objects.equals(binding.revision(), binding.readyRevision())
                ? "RESTARTING"
                : vm.status().name(),
            rules));
  }

  @Override
  public ResponseEntity<VmMetricsDto> metrics(UUID id, String range) {
    return ResponseEntity.ok(mapper.toDto(metrics.metrics(id, MetricsRange.of(range))));
  }

  @Override
  public ResponseEntity<HarnessDto> harness(UUID id) {
    var harness = harnesses.get(id);
    return ResponseEntity.ok(
        new HarnessDto(
            harness.image(), harness.environment(), harness.revision(), supervisor.forVm(id)));
  }
}
