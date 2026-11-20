package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes each demo once its time is up, whatever state it is in, and warns its owner once shortly
 * before. A demo is never charged, so {@link
 * io.github.kalpak44.workspacedai.api.billing.UsageCharger} never pauses or deletes one.
 */
@Component
public class DemoReaper {

  private static final Logger log = LoggerFactory.getLogger(DemoReaper.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceService service;
  private final NotificationService notifications;
  private final Duration warnBefore;

  public DemoReaper(
      WorkspaceRepository workspaces,
      WorkspaceService service,
      NotificationService notifications,
      @Value("${workspaced.demo.warn-before:PT30M}") Duration warnBefore) {
    this.workspaces = workspaces;
    this.service = service;
    this.notifications = notifications;
    this.warnBefore = warnBefore;
  }

  // Every minute, so a demo outlives its time by a minute at most; deleting waits for the VM.
  @Scheduled(fixedDelayString = "${workspaced.demo.reap-interval:PT1M}")
  @SchedulerLock(name = "workspace-demos", lockAtMostFor = "PT15M")
  public void run() {
    reap(Instant.now());
  }

  void reap(Instant now) {
    for (var demo :
        workspaces.findAllByDemoTrueAndExpiresAtBeforeOrderByExpiresAtAsc(now.plus(warnBefore))) {
      try {
        if (demo.getExpiresAt().isAfter(now)) {
          var minutes = Math.max(Duration.between(now, demo.getExpiresAt()).toMinutes(), 1);
          notifications.sendOnce(
              demo.getOwnerId(),
              "demo-ending:" + demo.getId(),
              Notifications.demoEnding(demo.getId(), demo.getName(), minutes, demo.getExpiresAt()));
        } else {
          service.deleteExpiredDemo(demo.getId());
        }
      } catch (RuntimeException e) {
        // The others are still reaped; this one is tried again on the next run.
        log.warn("could not reap demo workspace {}", demo.getId(), e);
      }
    }
  }
}
