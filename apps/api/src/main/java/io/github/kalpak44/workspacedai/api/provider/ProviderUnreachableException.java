package io.github.kalpak44.workspacedai.api.provider;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 502, so the client can tell an outage from a rejected key and keep what was typed. */
public class ProviderUnreachableException extends ResponseStatusException {

  public ProviderUnreachableException(ModelProvider provider, Throwable cause) {
    super(HttpStatus.BAD_GATEWAY, "could not reach " + provider.displayName(), cause);
  }
}
