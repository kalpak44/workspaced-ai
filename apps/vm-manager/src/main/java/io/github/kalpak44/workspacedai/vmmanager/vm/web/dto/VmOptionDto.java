package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(name = "VmOption", description = "A size a VM can be created as")
public record VmOptionDto(
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
    @Schema(
            description =
                "The VM's whole monthly cost before VAT, server and IPv4 address, in the Hetzner"
                    + " account's currency",
            example = "5.99")
        BigDecimal monthlyNet,
    @Schema(description = "The VM's whole monthly cost with VAT", example = "7.19")
        BigDecimal monthlyGross,
    @Schema(
            description = "One of the low-cost sizes, rather than a pricier stand-in",
            example = "true")
        boolean recommended,
    @Schema(
            description = "False while Hetzner has it sold out, when a create is refused",
            example = "true")
        boolean available,
    @Schema(description = "What the monthly cost is made of") List<VmCostDto> costs) {}
