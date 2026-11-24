package io.github.kalpak44.workspacedai.api.domain;

import java.time.Instant;
import java.util.UUID;

interface DomainMoves {

  /**
   * Moves it from one status to the other only if it is still in the first, so a check never brings
   * back one detached since it was read.
   *
   * @return whether it moved
   */
  int move(UUID id, DomainStatus from, DomainStatus to, Instant at);
}
