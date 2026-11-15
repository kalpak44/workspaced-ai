package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(name = "VmMetrics", description = "One range of a VM's metrics")
public record VmMetricsDto(
    @Schema(description = "Its virtual CPUs", example = "2") int cores,
    @Schema(description = "Its memory in GB", example = "4.0") double memoryGb,
    @Schema(description = "When each sample was taken, oldest first") List<Instant> timestamps,
    @Schema(
            description =
                "One value per timestamp, null where Hetzner had no sample: cpu (percent of all "
                    + "cores), diskReadBytes, diskWriteBytes, networkInBytes, networkOutBytes "
                    + "(bytes per second), diskReadIops, diskWriteIops, networkInPackets, "
                    + "networkOutPackets (per second)")
        Map<String, List<Double>> series,
    @Schema(description = "Its memory now; null when the VM has not reported for a while")
        VmMemoryDto memory) {}
