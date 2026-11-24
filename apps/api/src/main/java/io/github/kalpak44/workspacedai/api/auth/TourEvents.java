package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.auth.TourEventEntity.Kind;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Appends what a tour save changed, worked out here from the bookmark before and after rather than
 * reported by the web app, so an old tab cannot invent history.
 */
@Component
class TourEvents {

  private final TourEventRepository events;

  TourEvents(TourEventRepository events) {
    this.events = events;
  }

  /**
   * @param before what was stored before this save; null while no tour was under way
   * @param after what is stored now; null ends the tour, finished or not
   */
  void record(UUID user, TourProgress before, TourProgress after) {
    if (after == null) {
      if (before != null) {
        append(user, before.step(), Kind.ENDED);
      }
      return;
    }
    if (before == null || !after.step().equals(before.step())) {
      append(user, after.step(), Kind.REACHED);
    }
    var wasPaused = before != null && before.paused();
    if (after.paused() && !wasPaused) {
      append(user, after.step(), Kind.PAUSED);
    } else if (!after.paused() && wasPaused) {
      append(user, after.step(), Kind.RESUMED);
    }
  }

  private void append(UUID user, int step, Kind kind) {
    events.save(new TourEventEntity(user, step, kind));
  }
}
