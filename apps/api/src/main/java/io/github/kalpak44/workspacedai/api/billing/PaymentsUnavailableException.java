package io.github.kalpak44.workspacedai.api.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 503: no payment can be taken right now, though nothing is wrong with the request. */
public class PaymentsUnavailableException extends ResponseStatusException {

  public PaymentsUnavailableException(String reason, Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, "payments are unavailable: " + reason, cause);
  }
}
