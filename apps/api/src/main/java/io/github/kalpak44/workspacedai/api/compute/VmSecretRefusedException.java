package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 409 carrying the VM manager's own reason: the VM already has the secret, or is full. */
public class VmSecretRefusedException extends ResponseStatusException {

  public VmSecretRefusedException(String reason) {
    super(HttpStatus.CONFLICT, reason);
  }
}
