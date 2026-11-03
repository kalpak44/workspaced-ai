package io.github.kalpak44.workspacedai.api.harness;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 503 for a harness that is not running or not answering, so the client can retry. */
public class HarnessUnavailableException extends ResponseStatusException {

  public HarnessUnavailableException(String reason) {
    super(HttpStatus.SERVICE_UNAVAILABLE, reason);
  }

  public HarnessUnavailableException(String reason, Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
  }
}
