package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.github.kalpak44.workspacedai.vmmanager.vm.Vm;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmOption;
import org.springframework.stereotype.Component;

/** The one place the domain model turns into what the API answers. */
@Component
public class VmDtoMapper {

  public VmDto toDto(Vm vm) {
    return new VmDto(
        vm.id(),
        vm.ownerRef(),
        vm.serverType(),
        vm.status().name(),
        vm.ipv4(),
        vm.createdAt(),
        vm.lastSeenAt());
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
        option.monthlyGross());
  }
}
