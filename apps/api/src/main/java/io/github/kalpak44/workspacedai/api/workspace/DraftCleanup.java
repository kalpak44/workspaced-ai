package io.github.kalpak44.workspacedai.api.workspace;

import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes drafts nobody launched. The dialog deletes its own on cancel, but a closed tab or a
 * crashed browser never gets to, and a draft has no VM whose deletion would notice.
 */
@Component
public class DraftCleanup {

  private final WorkspaceRepository workspaces;
  private final Duration maxAge;

  public DraftCleanup(
      WorkspaceRepository workspaces,
      @Value("${workspaced.workspace.draft-max-age:PT1H}") Duration maxAge) {
    this.workspaces = workspaces;
    this.maxAge = maxAge;
  }

  @Scheduled(fixedDelayString = "${workspaced.workspace.draft-cleanup-interval:PT10M}")
  @SchedulerLock(name = "workspace-drafts", lockAtMostFor = "PT5M")
  public void run() {
    workspaces.deleteDraftsCreatedBefore(Instant.now().minus(maxAge));
  }
}
