package io.github.kalpak44.workspacedai.api.compute;

import io.github.kalpak44.workspacedai.api.referral.ReferralProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
  private static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(730);
  private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final VmManager vmManager;
  private final PlanProperties plans;
  private final ReferralProperties referrals;

  public VmPricing(VmManager vmManager, PlanProperties plans, ReferralProperties referrals) {
    this.vmManager = vmManager;
    this.plans = plans;
    this.referrals = referrals;
  }

  /**
   * Every configured tariff stays visible. Flex becomes the default only when normal plans sell
   * out.
   */
  public List<VmSize> sizes(boolean admin) {
    var sizes = vmManager.sizes();
    var regular = priced(plans.offered().stream().filter(plan -> !plan.fallback()), sizes, admin);
    var fallbacks =
        priced(plans.offered().stream().filter(PlanProperties.Plan::fallback), sizes, admin);
    var fallbackDefault =
        regular.stream().anyMatch(VmSize::available)
            ? null
            : fallbacks.stream().filter(VmSize::available).findFirst().orElse(null);
    var all = new ArrayList<VmSize>();
    regular.forEach(size -> all.add(size.withDefault(fallbackDefault == null && size.isDefault())));
    fallbacks.forEach(size -> all.add(size.withDefault(size == fallbackDefault)));
    return List.copyOf(all);
  }

  private List<VmSize> priced(
      Stream<PlanProperties.Plan> offered, List<VmSize> sizes, boolean admin) {
    var chosen = offered.toList();
    return chosen.stream()
        .flatMap(
            plan ->
                sizes.stream()
                    .filter(size -> size.serverType().equals(plan.serverType()))
                    .findFirst()
                    .map(size -> priced(plan, size, plan == chosen.getFirst(), admin))
                    .stream())
        .toList();
  }

  /**
   * The plan a user who is not an admin can create now, priced for them. Empty for a plan that is
   * sold out or withheld, which would otherwise be sold at a loss.
   *
   * @param serverType blank for the default plan, or the first available one while it is not
   */
  public Optional<VmSize> offered(String serverType) {
    var available = sizes(false).stream().filter(VmSize::available).toList();
    if (serverType == null || serverType.isBlank()) {
      return available.stream()
          .filter(VmSize::isDefault)
          .findFirst()
          .or(() -> available.stream().findFirst());
    }
    return available.stream()
        .filter(size -> size.serverType().equals(serverType.strip()))
        .findFirst();
  }

  /**
   * What a user who is not an admin pays a month for a plan's server, whether it is on sale now or
   * not, for a VM that already runs as it.
   */
  public Optional<BigDecimal> planPrice(String serverType) {
    return priced(plans.offered().stream(), vmManager.sizes(), false).stream()
        .filter(size -> size.serverType().equals(serverType))
        .findFirst()
        .map(VmSize::monthlyGross);
  }

  private VmSize priced(PlanProperties.Plan plan, VmSize size, boolean isDefault, boolean admin) {
    var monthly =
        plan.dailyPrice().multiply(HOURS_PER_MONTH).divide(HOURS_PER_DAY, 6, RoundingMode.HALF_UP);
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
        !plan.fallback(),
        plan.featured(),
        size.available() && covered);
  }

  /**
   * Whether the price keeps the minimum margin over the server's cost with VAT, which Hetzner's
   * hourly billing raises for a VM that lives less than a month, after Stripe's fee. Referral
   * credit is spent on servers too, so the most it can add for a paid euro raises the cost alike.
   */
  private boolean covers(PlanProperties.Plan plan, BigDecimal monthly, VmSize size) {
    var cost =
        size.monthlyGross()
            .multiply(plans.hourlyBillingFactor())
            .multiply(BigDecimal.ONE.add(referrals.rewardShare()));
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
