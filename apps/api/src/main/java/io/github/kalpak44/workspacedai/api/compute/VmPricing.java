package io.github.kalpak44.workspacedai.api.compute;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a user can create a workspace as: the configured plans, each priced at its fixed daily
 * price, or at cost for an admin. A plan whose server Hetzner no longer offers, or whose price no
 * longer covers that server's cost with the minimum margin, is not sold.
 */
@Component
public class VmPricing {

  private static final Logger LOG = LoggerFactory.getLogger(VmPricing.class);
  private static final BigDecimal DAYS_PER_MONTH =
      BigDecimal.valueOf(730).divide(BigDecimal.valueOf(24), 6, RoundingMode.HALF_UP);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final VmManager vmManager;
  private final PlanProperties plans;

  public VmPricing(VmManager vmManager, PlanProperties plans) {
    this.vmManager = vmManager;
    this.plans = plans;
  }

  /**
   * Every plan Hetzner still offers the server for, in the configured order, priced for the user.
   */
  public List<VmSize> sizes(boolean admin) {
    var sizes = vmManager.sizes();
    var first = plans.offered().getFirst();
    return plans.offered().stream()
        .flatMap(
            plan ->
                sizes.stream()
                    .filter(size -> size.serverType().equals(plan.serverType()))
                    .findFirst()
                    .map(size -> priced(plan, size, plan == first, admin))
                    .stream())
        .toList();
  }

  /**
   * What a user who is not an admin pays a month for the plan's server type, before VAT.
   *
   * @param serverType blank for the default plan
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

  private VmSize priced(PlanProperties.Plan plan, VmSize size, boolean isDefault, boolean admin) {
    var monthly = plan.dailyPrice().multiply(DAYS_PER_MONTH).setScale(6, RoundingMode.HALF_UP);
    var covered = covers(plan, monthly, size);
    return new VmSize(
        plan.name(),
        size.serverType(),
        isDefault,
        size.cores(),
        size.memoryGb(),
        size.diskGb(),
        size.cpuType(),
        size.architecture(),
        size.location(),
        admin ? size.monthlyNet() : monthly,
        admin ? size.monthlyGross() : monthly,
        true,
        size.available() && covered);
  }

  /**
   * Whether the price keeps the minimum margin over the server's cost with VAT, which Hetzner's
   * hourly billing raises for a VM that lives less than a month, after Stripe's fee.
   */
  private boolean covers(PlanProperties.Plan plan, BigDecimal monthly, VmSize size) {
    var cost = size.monthlyGross().multiply(plans.hourlyBillingFactor());
    var kept = monthly.multiply(BigDecimal.ONE.subtract(percent(plans.stripeFeePercent())));
    var floor = monthly.multiply(percent(plans.minMarginPercent()));
    if (kept.subtract(cost).compareTo(floor) >= 0) {
      return true;
    }
    LOG.error(
        "withholding plan {}: {} a month no longer covers {} at {} with the minimum margin",
        plan.name(),
        monthly.setScale(2, RoundingMode.HALF_UP),
        size.serverType(),
        cost.setScale(2, RoundingMode.HALF_UP));
    return false;
  }

  private static BigDecimal percent(BigDecimal value) {
    return value.divide(HUNDRED, 6, RoundingMode.HALF_UP);
  }
}
