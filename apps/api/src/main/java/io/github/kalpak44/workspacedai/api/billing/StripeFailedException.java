package io.github.kalpak44.workspacedai.api.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 502: Stripe refused or failed the call. */
public class StripeFailedException extends ResponseStatusException {

  public StripeFailedException(String reason) {
    super(HttpStatus.BAD_GATEWAY, "Stripe failed: " + reason);
  }
}
