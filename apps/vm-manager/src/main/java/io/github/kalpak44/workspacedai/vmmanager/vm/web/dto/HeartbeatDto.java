package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(name = "Heartbeat", description = "What a VM reports with its heartbeat")
public record HeartbeatDto(
    @Schema(description = "Its memory now; absent from a VM that reads none") @Valid
        MemoryReportDto memory) {}
