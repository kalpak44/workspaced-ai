package io.github.kalpak44.workspacedai.api.backup;

import java.net.URI;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Where archives are kept. The workspace moves the bytes itself, through URLs signed here, so the
 * storage keys never leave the API and no archive passes through it.
 */
public interface BackupStorage {

  URI uploadUrl(String key, Duration ttl);

  URI downloadUrl(String key, Duration ttl);

  /** The archive's size; empty when nothing is stored under that key. */
  OptionalLong size(String key);

  /** Deleting what is not there is not an error. */
  void delete(String key);
}
