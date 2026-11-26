package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "HeartbeatAnswer", description = "What a VM learns from its heartbeat")
public record HeartbeatAnswerDto(
    @Schema(
            description = "Where it stands",
            example = "READY",
            allowableValues = {"PROVISIONING", "READY", "RESTARTING", "STOPPED"})
        String status,
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "The inbound rules the VM applies itself; null when they cannot be read"
                            + " now, which leaves the ones it has in place",
                    nullable = true))
        List<FirewallRuleDto> firewall) {}
