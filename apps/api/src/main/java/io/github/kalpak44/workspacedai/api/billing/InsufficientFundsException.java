package io.github.kalpak44.workspacedai.api.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 402: the balance does not cover what was asked for; the reason says by how much. */
public class InsufficientFundsException extends ResponseStatusException {

  public InsufficientFundsException(String reason) {
    super(HttpStatus.PAYMENT_REQUIRED, reason);
  }
}
