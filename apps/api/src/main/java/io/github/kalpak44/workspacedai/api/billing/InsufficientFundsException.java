package io.github.kalpak44.workspacedai.api.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A 402: the balance does not cover what was asked for. The reason says by how much, and the
 * problem's shortCents property carries it, so a client can offer to add exactly that.
 */
public class InsufficientFundsException extends ResponseStatusException {

  private static final long MICROS_PER_CENT = 10_000;

  /**
   * @param shortMicros what the balance lacks, rounded up to a whole cent in the response
   */
  public InsufficientFundsException(String reason, long shortMicros) {
    super(HttpStatus.PAYMENT_REQUIRED, reason);
    getBody().setProperty("shortCents", Math.ceilDiv(shortMicros, MICROS_PER_CENT));
  }
}
