package io.github.kalpak44.workspacedai.api.auth;

import java.time.Instant;
import java.util.UUID;

/** Conditional updates of an account's one demo, each a single statement in its own transaction. */
public interface DemoClaims {

  /**
   * Takes the account's one demo; 0 when it already had it, or another start took it first.
   * Committed before the VM is asked for.
   */
  int claimDemo(UUID id, Instant now);

  /** Hands the demo back after its VM failed to start, so it can be tried again. */
  int releaseDemo(UUID id);
}
