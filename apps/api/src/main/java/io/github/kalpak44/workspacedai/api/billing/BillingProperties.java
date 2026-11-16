package io.github.kalpak44.workspacedai.api.billing;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param currency the one currency balances are kept and topped up in, as Stripe names it
 * @param vatPercent the VAT charged on top of every top-up
 * @param returnUrl the billing page Stripe Checkout sends the user back to
 * @param stripe the account payments go to
 */
@ConfigurationProperties("workspaced.billing")
public record BillingProperties(
    @DefaultValue("eur") String currency,
    @DefaultValue("20") int vatPercent,
    @DefaultValue("http://localhost:5173/billing") URI returnUrl,
    @DefaultValue Stripe stripe) {

  /**
   * @param secretKey a restricted key with write access to Customers and Checkout Sessions and read
   *     access to Invoices and Tax Rates; unset, as it is locally, no top-up can start and they
   *     answer 503
   * @param webhookSecret signs the events Stripe sends; unset, the webhook answers 503
   * @param url the API root, replaced only by tests
   * @param timeout how long one call may take
   */
  public record Stripe(
      String secretKey,
      String webhookSecret,
      @DefaultValue("https://api.stripe.com") URI url,
      @DefaultValue("20s") Duration timeout) {

    boolean isConfigured() {
      return secretKey != null && !secretKey.isBlank();
    }
  }
}
