package io.github.kalpak44.workspacedai.api.firewall;

import io.github.kalpak44.workspacedai.api.compute.FirewallRule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Every inbound rule the workspace's firewall should have")
public record FirewallRulesRequest(
    @NotNull @Size(max = 50) List<@Valid @NotNull FirewallRule> rules) {}
