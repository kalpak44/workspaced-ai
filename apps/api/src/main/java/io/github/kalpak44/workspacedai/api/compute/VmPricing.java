package io.github.kalpak44.workspacedai.api.compute;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What a user pays for a VM: its cost for an admin, and its cost with the markup for everyone else.
 */
@Component
public class VmPricing {

  private final VmManager vmManager;
  private final BigDecimal markup;

  public VmPricing(VmManager vmManager, VmPricingProperties properties) {
    this.vmManager = vmManager;
    this.markup = BigDecimal.ONE.add(properties.markupPercent().movePointLeft(2));
  }

  /** Every size on offer, priced for the user. */
  public List<VmSize> sizes(boolean admin) {
    var sizes = vmManager.sizes();
    return admin ? sizes : sizes.stream().map(size -> size.pricedBy(markup)).toList();
  }

  /**
   * What a user who is not an admin pays a month for the size, with VAT.
   *
   * @param serverType blank for the default size
   */
  public Optional<BigDecimal> monthlyPrice(String serverType) {
    return sizes(false).stream()
        .filter(
            size ->
                serverType == null || serverType.isBlank()
                    ? size.isDefault()
                    : size.serverType().equals(serverType.strip()))
        .findFirst()
        .map(VmSize::monthlyGross);
  }
}
