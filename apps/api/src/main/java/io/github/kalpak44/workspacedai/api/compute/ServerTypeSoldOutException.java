package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 409: Hetzner has the size sold out right now, so the user picks another or waits. */
public class ServerTypeSoldOutException extends ResponseStatusException {

  public ServerTypeSoldOutException(String reason) {
    super(HttpStatus.CONFLICT, reason);
  }
}
