package io.github.kalpak44.workspacedai.api.compute;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The plans users choose a workspace by, each a fixed daily price for one Hetzner server type, and
 * what a plan must earn over that server's cost to be sold.
 *
 * @param offered in the order they are shown; the first is the default
 * @param hourlyBillingFactor how much more Hetzner charges an hour than its monthly price spread
 *     over 730, before the monthly cap is reached
 * @param stripeFeePercent the worst share of a top-up Stripe keeps
 * @param minMarginPercent what a plan must keep of its price after the server's cost and Stripe's
 *     fee, or it is withheld
 */
@Validated
@ConfigurationProperties("workspaced.plans")
public record PlanProperties(
    @NotEmpty List<@Valid Plan> offered,
    @NotNull @DecimalMin("1") BigDecimal hourlyBillingFactor,
    @NotNull @DecimalMin("0") BigDecimal stripeFeePercent,
    @NotNull @DecimalMin("0") BigDecimal minMarginPercent) {

  /**
   * @param name what users see, e.g. Starter
   * @param serverType the Hetzner server type behind it
   * @param dailyPrice what a user who is not an admin pays a day, before VAT, in EUR
   */
  public record Plan(
      @NotBlank String name,
      @NotBlank String serverType,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal dailyPrice) {}
}
