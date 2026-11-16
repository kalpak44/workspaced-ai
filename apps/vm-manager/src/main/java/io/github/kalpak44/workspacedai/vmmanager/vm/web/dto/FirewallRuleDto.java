package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(name = "FirewallRule", description = "One inbound rule of a VM's firewall")
public record FirewallRuleDto(
    @Schema(
            allowableValues = {"tcp", "udp", "icmp"},
            example = "tcp")
        @NotEmpty
        String protocol,
    @Schema(
            description = "One port or a range; required for tcp and udp, absent for icmp",
            example = "8080",
            nullable = true)
        @Size(max = 11)
        String port,
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "Networks in CIDR notation, or single addresses; empty lets in anyone"),
            schema = @Schema(example = "0.0.0.0/0"))
        @Size(max = 20)
        List<String> sourceIps,
    @Schema(example = "Blog", nullable = true) @Size(max = 255) String description) {}
