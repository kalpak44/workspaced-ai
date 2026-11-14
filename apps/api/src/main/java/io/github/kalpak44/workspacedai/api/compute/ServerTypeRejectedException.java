package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 400 carrying the VM manager's own reason, which names the sizes it does offer. */
public class ServerTypeRejectedException extends ResponseStatusException {

  public ServerTypeRejectedException(String reason) {
    super(HttpStatus.BAD_REQUEST, reason);
  }
}
