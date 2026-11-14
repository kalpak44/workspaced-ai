package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 502: the VM manager, or Hetzner behind it, failed the call. */
public class VmManagerFailedException extends ResponseStatusException {

  public VmManagerFailedException(String reason) {
    super(HttpStatus.BAD_GATEWAY, "the VM manager failed: " + reason);
  }
}
