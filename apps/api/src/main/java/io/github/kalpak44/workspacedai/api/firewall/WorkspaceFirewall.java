package io.github.kalpak44.workspacedai.api.firewall;

import io.github.kalpak44.workspacedai.api.compute.FirewallRule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "What may reach a workspace's VM from the internet")
public record WorkspaceFirewall(
    @Schema(
            description =
                "The VM's public IPv4, which every rule opens a port on; null until it has one",
            example = "203.0.113.10",
            nullable = true)
        String address,
    @Schema(description = "Whether the workspace's agent may open and close ports itself")
        boolean agentAllowed,
    @Schema(description = "Every inbound rule; a packet no rule allows is dropped")
        List<FirewallRule> rules) {}
