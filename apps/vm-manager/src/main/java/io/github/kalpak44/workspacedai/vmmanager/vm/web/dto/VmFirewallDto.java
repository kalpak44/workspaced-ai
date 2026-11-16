package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(
    name = "VmFirewall",
    description = "Every inbound rule of a VM's firewall; a packet no rule allows is dropped")
public record VmFirewallDto(@NotNull @Size(max = 50) List<@Valid @NotNull FirewallRuleDto> rules) {}
