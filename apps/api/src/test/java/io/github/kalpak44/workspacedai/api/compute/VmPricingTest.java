package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.referral.ReferralProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plans are sold at their fixed price only while it covers their server's cost. */
class VmPricingTest {

  private static final PlanProperties PLANS =
      new PlanProperties(
          List.of(
              new PlanProperties.Plan("Starter", "cx23", new BigDecimal("0.35"), false, false),
              new PlanProperties.Plan("Builder", "cx33", new BigDecimal("0.55"), false, true),
              new PlanProperties.Plan(
                  "Starter Flex", "cpx22", new BigDecimal("1.10"), true, false)),
          new BigDecimal("1.155"),
          BigDecimal.valueOf(7),
          BigDecimal.valueOf(10));

  private static final ReferralProperties.Side TEN_PERCENT =
      new ReferralProperties.Side(BigDecimal.TEN, BigDecimal.TEN, Duration.ofDays(90));

  private final VmManager vmManager = mock(VmManager.class);
  private final VmPricing pricing = new VmPricing(vmManager, PLANS, referrals(false));

  @Test
  void offersThePlansInOrderAtTheirDailyPriceAndLeavesOtherServersOut() {
    when(vmManager.sizes())
        .thenReturn(
            List.of(
                server("cpx22", "23.99", true),
                server("cx33", "10.79", true),
                server("cx23", "7.19", false)));

    var sizes = pricing.sizes(false);

    assertThat(sizes).extracting(VmSize::plan).containsExactly("Starter", "Builder");
    assertThat(sizes.getFirst().isDefault()).isTrue();
    assertThat(sizes.getFirst().available()).isFalse();
    assertThat(sizes.getFirst().monthlyGross()).isEqualByComparingTo("10.645833");
    assertThat(sizes.get(1).monthlyGross()).isEqualByComparingTo("16.729167");
    assertThat(sizes).extracting(VmSize::featured).containsExactly(false, true);
  }

  @Test
  void offersTheFallbackPlanAsTheDefaultOnlyWhileEveryOtherPlanIsSoldOut() {
    when(vmManager.sizes())
        .thenReturn(
            List.of(
                server("cx23", "7.19", false),
                server("cx33", "10.79", false),
                server("cpx22", "23.99", true)));

    var sizes = pricing.sizes(false);

    assertThat(sizes)
        .extracting(VmSize::plan)
        .containsExactly("Starter", "Builder", "Starter Flex");
    assertThat(sizes).extracting(VmSize::isDefault).containsExactly(false, false, true);
    assertThat(sizes.get(2).recommended()).isFalse();
    assertThat(pricing.offered(null))
        .hasValueSatisfying(
            size -> assertThat(size.monthlyGross()).isEqualByComparingTo("33.458333"));

    when(vmManager.sizes())
        .thenReturn(
            List.of(
                server("cx23", "7.19", false),
                server("cx33", "10.79", true),
                server("cpx22", "23.99", true)));
    assertThat(pricing.sizes(false)).extracting(VmSize::plan).containsExactly("Starter", "Builder");
  }

  @Test
  void showsAnAdminTheServersCost() {
    when(vmManager.sizes()).thenReturn(List.of(server("cx23", "7.19", true)));

    assertThat(pricing.sizes(true).getFirst().monthlyGross()).isEqualByComparingTo("7.19");
  }

  @Test
  void withholdsAPlanWhosePriceNoLongerCoversItsServer() {
    // 10.65 less 7% leaves 9.90, short of 8.66 times 1.155 plus a 10% margin.
    when(vmManager.sizes()).thenReturn(List.of(server("cx23", "8.66", true)));

    assertThat(pricing.sizes(false).getFirst().available()).isFalse();
    assertThat(pricing.offered("cx23")).isEmpty();
    assertThat(pricing.offered(null)).isEmpty();
  }

  @Test
  void sellsNoPlanTheUserCannotSeeOnSaleAndTakesTheFirstOnSaleForABlankType() {
    when(vmManager.sizes())
        .thenReturn(
            List.of(
                server("cx23", "7.19", false),
                server("cx33", "10.79", true),
                server("cpx22", "23.99", true)));

    assertThat(pricing.offered("cx23")).isEmpty();
    assertThat(pricing.offered("cpx22")).isEmpty();
    assertThat(pricing.offered(null)).map(VmSize::serverType).hasValue("cx33");
    assertThat(pricing.offered(" cx33 ")).map(VmSize::serverType).hasValue("cx33");
  }

  @Test
  void countsReferralCreditAsServerCostSoAPlanStaysProfitableWithIt() {
    when(vmManager.sizes()).thenReturn(List.of(server("cx23", "7.19", true)));
    assertThat(pricing.sizes(false).getFirst().available()).isTrue();

    // 10.65 less 7% leaves 9.90; 7.19 times 1.155 is 8.30, and 9.97 with 20% of it as credit.
    var withReferrals = new VmPricing(vmManager, PLANS, referrals(true));
    assertThat(withReferrals.sizes(false).getFirst().available()).isFalse();
  }

  private static ReferralProperties referrals(boolean enabled) {
    return new ReferralProperties(
        enabled, URI.create("https://workspaced.online/"), TEN_PERCENT, TEN_PERCENT);
  }

  private static VmSize server(String type, String monthlyGross, boolean available) {
    return new VmSize(
        null,
        type,
        false,
        2,
        4,
        40,
        "shared",
        "x86",
        "nbg1",
        new BigDecimal(monthlyGross),
        new BigDecimal(monthlyGross),
        false,
        false,
        available);
  }
}
