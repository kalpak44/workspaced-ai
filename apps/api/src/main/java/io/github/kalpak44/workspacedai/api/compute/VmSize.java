package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "A size a Hetzner workspace can be created as")
public record VmSize(
    @Schema(description = "The serverType to create it with", example = "cx23") String serverType,
    @Schema(description = "Taken when a create names no serverType", example = "true")
        boolean isDefault,
    @Schema(description = "Virtual CPUs", example = "2") int cores,
    @Schema(description = "Memory in GB", example = "4.0") double memoryGb,
    @Schema(description = "Included disk in GB", example = "40") int diskGb,
    @Schema(description = "shared or dedicated", example = "shared") String cpuType,
    @Schema(description = "CPU architecture", example = "x86") String architecture,
    @Schema(description = "The Hetzner location it is created in", example = "nbg1")
        String location,
    @Schema(description = "Monthly price before VAT, in EUR", example = "5.49")
        BigDecimal monthlyNet,
    @Schema(description = "Monthly price with VAT, in EUR", example = "6.59")
        BigDecimal monthlyGross) {}
