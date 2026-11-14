package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Vm", description = "A VM one workspace runs on")
public record VmDto(
    @Schema(
            description = "Assigned by the server",
            example = "8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21")
        UUID id,
    @Schema(description = "The workspace it runs", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
        UUID workspaceId,
    @Schema(
            description = "Who owns the workspace",
            example = "00000000-0000-0000-0000-000000000001")
        UUID userId,
    @Schema(description = "Hetzner server type", example = "cx23") String serverType,
    @Schema(description = "The Hetzner server it runs as", example = "112233445") long serverId,
    @Schema(
            description = "Where it stands",
            example = "PROVISIONING",
            allowableValues = {"PROVISIONING", "READY", "RESTARTING"})
        String status,
    @Schema(description = "Public IPv4; null until it has one", example = "203.0.113.10")
        String ipv4,
    @Schema(description = "When it was requested") Instant createdAt,
    @Schema(description = "When it last reported ready or a heartbeat; null before it has")
        Instant lastSeenAt) {}
