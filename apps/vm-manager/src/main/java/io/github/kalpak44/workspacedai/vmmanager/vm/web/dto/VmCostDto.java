package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "VmCost", description = "One part of what a VM costs a month")
public record VmCostDto(
    @Schema(description = "What is paid for: server or ipv4", example = "server") String item,
    @Schema(description = "Monthly before VAT, in the Hetzner account's currency", example = "5.49")
        BigDecimal monthlyNet,
    @Schema(description = "Monthly with VAT", example = "6.59") BigDecimal monthlyGross) {}
