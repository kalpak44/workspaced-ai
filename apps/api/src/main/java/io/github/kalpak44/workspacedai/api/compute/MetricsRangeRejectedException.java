package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 400 carrying the VM manager's own reason, which names the ranges it does offer. */
public class MetricsRangeRejectedException extends ResponseStatusException {

  public MetricsRangeRejectedException(String reason) {
    super(HttpStatus.BAD_REQUEST, reason);
  }
}
