package io.github.kalpak44.workspacedai.api.backup;

/** Where a backup is: still being made, in storage and restorable, or abandoned. */
public enum BackupStatus {
  RUNNING,
  COMPLETED,
  FAILED
}
