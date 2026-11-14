package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** A Hetzner server type, with only the fields this app reads. */
public record ServerType(
    String name,
    int cores,
    double memoryGb,
    int diskGb,
    String cpuType,
    String architecture,
    boolean deprecated,
    List<Price> prices) {

  /** Hetzner lists a price only for the locations it sells the type in. */
  public Optional<Price> priceIn(String location) {
    return prices.stream().filter(price -> price.location().equals(location)).findFirst();
  }

  /** Monthly, in the currency of the Hetzner account. */
  public record Price(String location, BigDecimal monthlyNet, BigDecimal monthlyGross) {}
}
