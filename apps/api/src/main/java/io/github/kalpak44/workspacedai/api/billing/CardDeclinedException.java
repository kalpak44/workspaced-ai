package io.github.kalpak44.workspacedai.api.billing;

/** Stripe refused to charge the saved card; the reason is Stripe's own, fit to show the user. */
public class CardDeclinedException extends RuntimeException {

  public CardDeclinedException(String reason) {
    super(reason);
  }
}
