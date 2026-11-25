package io.github.kalpak44.workspacedai.api.referral;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The referral program: both sides earn balance credit on the invited user's paid top-ups.
 *
 * @param link the page an invitation opens, which carries the code on to sign-up
 * @param referee what the invited user gets on top of their own top-ups
 * @param referrer what the user who invited them gets
 */
@Validated
@ConfigurationProperties("workspaced.referrals")
public record ReferralProperties(
    boolean enabled,
    @NotNull URI link,
    @NotNull @Valid Side referee,
    @NotNull @Valid Side referrer) {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /**
   * @param percent of each paid top-up before VAT
   * @param cap the most it ever adds up to for one invited user, in EUR
   * @param period how long after the invited user's sign-up their top-ups count
   */
  public record Side(
      @NotNull @DecimalMin("0") @DecimalMax("50") BigDecimal percent,
      @NotNull @DecimalMin("0") BigDecimal cap,
      @NotNull Duration period) {}

  /**
   * The most both rewards can add for one paid euro, as a fraction; zero while the program is off.
   * Plans are priced to stay profitable with it.
   */
  public BigDecimal rewardShare() {
    return enabled ? referee.percent().add(referrer.percent()).divide(HUNDRED) : BigDecimal.ZERO;
  }
}
