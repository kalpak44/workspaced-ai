package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "One range of a workspace VM's metrics")
public record WorkspaceMetrics(
    @Schema(description = "The VM's virtual CPUs", example = "2") int cores,
    @Schema(description = "The VM's memory in GB", example = "4.0") double memoryGb,
    @Schema(description = "When each sample was taken, oldest first") List<Instant> timestamps,
    @Schema(
            description =
                "One value per timestamp, null where there was no sample: cpu (percent of all "
                    + "cores), diskReadBytes, diskWriteBytes, networkInBytes, networkOutBytes "
                    + "(bytes per second), diskReadIops, diskWriteIops, networkInPackets, "
                    + "networkOutPackets (per second)")
        Map<String, List<Double>> series,
    @Schema(description = "The VM's memory now; null when it has not reported for a while")
        Memory memory) {

  @Schema(name = "WorkspaceMemory", description = "A VM's memory as it last reported it")
  public record Memory(
      @Schema(example = "4005847040") long totalBytes,
      @Schema(
              description = "What the kernel could hand out without swapping, cache included",
              example = "2945581056")
          long availableBytes,
      @Schema(description = "When the VM reported it") Instant reportedAt) {}
}
