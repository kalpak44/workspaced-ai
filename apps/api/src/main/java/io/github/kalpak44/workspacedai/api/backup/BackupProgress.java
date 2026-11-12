package io.github.kalpak44.workspacedai.api.backup;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Follows each running backup by reading the report its workspace writes, and records the progress
 * on the backup's row, which is what the app polls. Nothing is held in memory that a restart would
 * lose: the workspace keeps working without the API, so a backup the API was following when it
 * stopped is picked up again when it starts.
 */
@Component
public class BackupProgress {

  private static final Logger LOG = LoggerFactory.getLogger(BackupProgress.class);
  // Archiving reads every byte of the home and uploading sends a fraction of that, so archiving
  // takes most of the bar.
  private static final int ARCHIVED = 80;
  private static final int UPLOADED = 99;

  private final BackupRepository backups;
  private final BackupStorage storage;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final BackupProperties properties;
  private final JsonMapper json;

  public BackupProgress(
      BackupRepository backups,
      BackupStorage storage,
      WorkspaceRuntime runtime,
      Harness harness,
      BackupProperties properties,
      JsonMapper json) {
    this.backups = backups;
    this.storage = storage;
    this.runtime = runtime;
    this.harness = harness;
    this.properties = properties;
    this.json = json;
  }

  @EventListener(ApplicationReadyEvent.class)
  void resume() {
    for (var backup : backups.findAllByStatus(BackupStatus.RUNNING)) {
      follow(backup.getId(), backup.getWorkspaceId(), backup.getObjectKey());
    }
  }

  void follow(UUID backupId, UUID workspaceId, String key) {
    Thread.ofVirtual().name("backup-" + backupId).start(() -> watch(backupId, workspaceId, key));
  }

  private void watch(UUID backupId, UUID workspaceId, String key) {
    var lastReport = "";
    var lastChange = Instant.now();
    while (true) {
      try {
        Thread.sleep(properties.pollInterval());
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        var report = report(workspaceId).orElse(null);
        if (report != null && backupId.toString().equals(report.path("id").asString(""))) {
          if (!report.toString().equals(lastReport)) {
            lastReport = report.toString();
            lastChange = Instant.now();
          }
          if (settled(backupId, key, report)) {
            return;
          }
        }
        if (Instant.now().isAfter(lastChange.plus(properties.stallTimeout()))) {
          backups.fail(backupId, "the workspace stopped reporting progress", now());
          return;
        }
        if (backups
            .findById(backupId)
            .filter(b -> b.getStatus() == BackupStatus.RUNNING)
            .isEmpty()) {
          return;
        }
      } catch (RuntimeException e) {
        // A storage or database hiccup is retried on the next round; the stall timeout ends it.
        LOG.debug("could not read the progress of backup {}", backupId, e);
      }
    }
  }

  /** True once the backup is finished one way or the other. */
  private boolean settled(UUID backupId, String key, JsonNode report) {
    var done = report.path("done").asLong(0);
    var total = report.path("total").asLong(0);
    switch (report.path("phase").asString("")) {
      case "archiving" -> backups.progress(backupId, "archiving", share(done, total, 1, ARCHIVED));
      case "uploading" ->
          backups.progress(backupId, "uploading", share(done, total, ARCHIVED, UPLOADED));
      case "done" -> {
        // The workspace saying it uploaded is not the same as the archive being there.
        var size = storage.size(key);
        if (size.isPresent()) {
          backups.complete(backupId, size.getAsLong(), now());
        } else {
          backups.fail(backupId, "the workspace uploaded nothing", now());
        }
        return true;
      }
      case "failed" -> {
        var error = report.path("error").asString("the workspace could not make the backup");
        backups.fail(backupId, error.length() > 500 ? error.substring(0, 500) : error, now());
        return true;
      }
      default -> {
        // A phase this API does not know yet; the next report may be one it does.
      }
    }
    return false;
  }

  private Optional<JsonNode> report(UUID workspaceId) {
    return runtime.harness(workspaceId).flatMap(harness::backupStatus).map(json::readTree);
  }

  static int share(long done, long total, int from, int to) {
    if (total <= 0) {
      return to;
    }
    return from + (int) ((to - from) * Math.min(done, total) / total);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
