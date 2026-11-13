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

  /** Starts an archive that arrives in parts, and returns the id the parts go under. */
  String startUpload(String key);

  /** Sending a part number again replaces it. */
  void uploadPart(String key, String uploadId, int part, byte[] bytes);

  /** Joins every part received, in order, into the archive under {@code key}. */
  void completeUpload(String key, String uploadId);

  /** Discards the parts received; aborting an upload already gone is not an error. */
  void abortUpload(String key, String uploadId);
}
