package io.github.kalpak44.workspacedai.agenthub.caller;

import java.util.Optional;

/** Says which service account a caller's token belongs to. */
public interface CallerVerifier {

  /** Empty when the token is not one the cluster issued for this hub, or has expired. */
  Optional<String> verify(String token);
}
