package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.github.kalpak44.workspacedai.vmmanager.vm.Vm;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmMetrics;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmOption;
import org.springframework.stereotype.Component;

/** The one place the domain model turns into what the API answers. */
@Component
public class VmDtoMapper {

  public VmDto toDto(Vm vm) {
    return new VmDto(
        vm.id(),
        vm.workspaceId(),
        vm.userId(),
        vm.serverType(),
        vm.serverId(),
        vm.status().name(),
        vm.ipv4(),
        vm.createdAt(),
        vm.lastSeenAt());
  }

  public VmMetricsDto toDto(VmMetrics metrics) {
    var memory = metrics.memory();
    return new VmMetricsDto(
        metrics.cores(),
        metrics.memoryGb(),
        metrics.timestamps(),
        metrics.series(),
        memory == null
            ? null
            : new VmMemoryDto(memory.totalBytes(), memory.availableBytes(), memory.reportedAt()));
  }

  public VmOptionDto toDto(VmOption option) {
    return new VmOptionDto(
        option.serverType(),
        option.isDefault(),
        option.cores(),
        option.memoryGb(),
        option.diskGb(),
        option.cpuType(),
        option.architecture(),
        option.location(),
        option.monthlyNet(),
        option.monthlyGross(),
        option.recommended(),
        option.available());
  }
}
