package io.github.kalpak44.workspacedai.api.session;

import java.util.UUID;

/** Single-statement updates of a session row, which skip a row deleted meanwhile. */
public interface SessionUpdates {

  /** Its own transaction: it runs on a stream's reader thread, outside any request. */
  int retitle(UUID id, String title);

  /**
   * A row deleted meanwhile is skipped rather than failing, as a dirty entity's update would. It
   * clears the loaded entities, so a read that follows sees the change.
   */
  int reconcile(UUID id, boolean available, String title);
}
