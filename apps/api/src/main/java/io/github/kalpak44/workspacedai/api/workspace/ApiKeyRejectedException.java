package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 422 of its own, so the client can point at the key field rather than the whole form. */
public class ApiKeyRejectedException extends ResponseStatusException {

  public ApiKeyRejectedException(ModelProvider provider) {
    super(HttpStatus.UNPROCESSABLE_CONTENT, provider.displayName() + " rejected the API key");
  }
}
