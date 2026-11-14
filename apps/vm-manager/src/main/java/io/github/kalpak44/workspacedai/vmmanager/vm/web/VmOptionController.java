package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.VmOptionService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDtoMapper;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmOptionDto;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link VmOptionApi}. */
@RestController
public class VmOptionController implements VmOptionApi {

  private final VmOptionService service;
  private final VmDtoMapper mapper;

  public VmOptionController(VmOptionService service, VmDtoMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<List<VmOptionDto>> list() {
    return ResponseEntity.ok(service.list().stream().map(mapper::toDto).toList());
  }
}
