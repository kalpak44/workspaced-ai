package io.github.kalpak44.workspacedai.api.compute;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link VmSizeApi}. */
@RestController
public class VmSizeController implements VmSizeApi {

  private final VmManager vmManager;

  public VmSizeController(VmManager vmManager) {
    this.vmManager = vmManager;
  }

  @Override
  public ResponseEntity<List<VmSize>> list() {
    return ResponseEntity.ok(vmManager.sizes());
  }
}
