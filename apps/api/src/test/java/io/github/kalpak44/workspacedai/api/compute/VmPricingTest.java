package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plans are sold at their fixed price only while it covers their server's cost. */
class VmPricingTest {

  private static final PlanProperties PLANS =
      new PlanProperties(
          List.of(
              new PlanProperties.Plan("Starter", "cx23", new BigDecimal("0.35"), false),
              new PlanProperties.Plan("Builder", "cx33", new BigDecimal("0.55"), false),
              new PlanProperties.Plan("Starter Flex", "cpx22", new BigDecimal("1.10"), true)),
          new BigDecimal("1.155"),
          BigDecimal.valueOf(7),
          BigDecimal.valueOf(10));

  private final VmManager vmManager = mock(VmManager.class);
  private final VmPricing pricing = new VmPricing(vmManager, PLANS);

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
    assertThat(pricing.monthlyPrice(null))
        .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("33.458333"));

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
    assertThat(pricing.monthlyPrice("cx23")).isPresent();
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
        available);
  }
}
