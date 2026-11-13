package io.github.kalpak44.workspacedai.api.backup;

import java.net.URI;
import java.time.Duration;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stands in while no endpoint is set, so a local run starts and backups answer 503. */
class UnconfiguredBackupStorage implements BackupStorage {

  @Override
  public URI uploadUrl(String key, Duration ttl) {
    throw unavailable();
  }

  @Override
  public URI downloadUrl(String key, Duration ttl) {
    throw unavailable();
  }

  @Override
  public OptionalLong size(String key) {
    throw unavailable();
  }

  @Override
  public void delete(String key) {
    throw unavailable();
  }

  @Override
  public String startUpload(String key) {
    throw unavailable();
  }

  @Override
  public void uploadPart(String key, String uploadId, int part, byte[] bytes) {
    throw unavailable();
  }

  @Override
  public void completeUpload(String key, String uploadId) {
    throw unavailable();
  }

  @Override
  public void abortUpload(String key, String uploadId) {
    throw unavailable();
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "backups are not configured: set WORKSPACED_BACKUP_ENDPOINT and its keys");
  }
}
