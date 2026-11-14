package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 503: no workspace VM can be made right now, though nothing is wrong with the request. */
public class VmManagerUnavailableException extends ResponseStatusException {

  public VmManagerUnavailableException(String reason, Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, "workspace VMs are unavailable: " + reason, cause);
  }
}
