package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FirewallRule;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmFirewallService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.FirewallRuleDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmFirewallDto;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes, validation and documentation are declared on {@link VmFirewallApi}; errors are answered
 * by {@link ApiExceptionHandler}.
 */
@RestController
public class VmFirewallController implements VmFirewallApi {

  private final VmFirewallService service;

  public VmFirewallController(VmFirewallService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<VmFirewallDto> get(UUID id) {
    return ResponseEntity.ok(toDto(service.rules(id)));
  }

  @Override
  public ResponseEntity<VmFirewallDto> set(UUID id, VmFirewallDto body) {
    var rules =
        body.rules().stream()
            .map(r -> new FirewallRule(r.protocol(), r.port(), r.sourceIps(), r.description()))
            .toList();
    return ResponseEntity.ok(toDto(service.setRules(id, rules)));
  }

  private static VmFirewallDto toDto(List<FirewallRule> rules) {
    return new VmFirewallDto(
        rules.stream()
            .map(r -> new FirewallRuleDto(r.protocol(), r.port(), r.sourceIps(), r.description()))
            .toList());
  }
}
