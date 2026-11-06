package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs without a browser connection; unavailable workspaces are retried on the next pass. One API
 * instance runs a pass at a time: the others find the lock held and skip theirs. Holding it for at
 * least a while after a pass keeps the instances from each running one straight after another.
 */
@Component
public class SessionExpiryScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(SessionExpiryScheduler.class);
  private final WorkspaceRepository workspaces;
  private final SessionExpiryService expiry;

  public SessionExpiryScheduler(WorkspaceRepository workspaces, SessionExpiryService expiry) {
    this.workspaces = workspaces;
    this.expiry = expiry;
  }

  @Scheduled(
      fixedDelayString = "${workspaced.session-ttl.cleanup-delay-ms:60000}",
      initialDelayString = "${workspaced.session-ttl.cleanup-delay-ms:60000}")
  @SchedulerLock(
      name = "session-expiry",
      lockAtLeastFor = "${workspaced.session-ttl.cleanup-lock-at-least:PT30S}")
  public void cleanup() {
    for (var workspaceId : workspaces.findTtlEnabledIds()) {
      try {
        for (var id : expiry.candidates(workspaceId)) {
          try {
            expiry.expire(id);
          } catch (RuntimeException e) {
            LOG.warn("Could not expire session {}; will retry", id, e);
          }
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not check session TTL for workspace {}; will retry", workspaceId, e);
      }
    }
  }
}
