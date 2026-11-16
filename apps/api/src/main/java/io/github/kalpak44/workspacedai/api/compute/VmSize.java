package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
    @Schema(
            description = "The VM's whole monthly cost before VAT, server and IPv4 address, in EUR",
            example = "5.99")
        BigDecimal monthlyNet,
    @Schema(description = "The VM's whole monthly cost with VAT, in EUR", example = "7.19")
        BigDecimal monthlyGross,
    @Schema(
            description = "One of the low-cost sizes, rather than a pricier stand-in",
            example = "true")
        boolean recommended,
    @Schema(
            description = "False while Hetzner has it sold out, when a create is refused",
            example = "true")
        boolean available) {

  /** The same size priced up by a factor, to the cent. */
  VmSize pricedBy(BigDecimal factor) {
    return new VmSize(
        serverType,
        isDefault,
        cores,
        memoryGb,
        diskGb,
        cpuType,
        architecture,
        location,
        monthlyNet.multiply(factor).setScale(2, RoundingMode.HALF_UP),
        monthlyGross.multiply(factor).setScale(2, RoundingMode.HALF_UP),
        recommended,
        available);
  }
}
