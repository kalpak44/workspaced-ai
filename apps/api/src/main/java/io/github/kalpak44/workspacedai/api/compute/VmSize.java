package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "A plan a workspace can be created as, and the Hetzner server behind it")
public record VmSize(
    @Schema(
            description = "What users choose it by; null as the VM manager reports a server",
            example = "Starter")
        String plan,
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
                "What a user pays a month before VAT, in EUR; for an admin, the server and IPv4"
                    + " address at cost before VAT",
            example = "10.645833")
        BigDecimal monthlyNet,
    @Schema(
            description =
                "What a user pays a month before VAT, in EUR; for an admin, the cost with VAT",
            example = "10.645833")
        BigDecimal monthlyGross,
    @Schema(
            description =
                "False for a plan offered only while every other plan is sold out, on a pricier"
                    + " server",
            example = "true")
        boolean recommended,
    @Schema(
            description = "The plan most users should take, highlighted where the plans are shown",
            example = "false")
        boolean featured,
    @Schema(
            description = "False while Hetzner has it sold out, when a create is refused",
            example = "true")
        boolean available) {

  /** The same size, marked as the default or not. */
  VmSize withDefault(boolean isDefault) {
    return new VmSize(
        plan,
        serverType,
        isDefault,
        cores,
        memoryGb,
        diskGb,
        cpuType,
        architecture,
        location,
        monthlyNet,
        monthlyGross,
        recommended,
        featured,
        available);
  }
}
