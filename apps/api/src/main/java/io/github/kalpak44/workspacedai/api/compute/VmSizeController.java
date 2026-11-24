package io.github.kalpak44.workspacedai.api.compute;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link VmSizeApi}. */
@RestController
public class VmSizeController implements VmSizeApi {

  private final VmPricing pricing;

  public VmSizeController(VmPricing pricing) {
    this.pricing = pricing;
  }

  /** An admin sees what each plan's VM costs; everyone else sees the plan's price. */
  @Override
  public ResponseEntity<List<VmSize>> list() {
    return ResponseEntity.ok(pricing.sizes(CurrentUser.admin()));
  }
}
