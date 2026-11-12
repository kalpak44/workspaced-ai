package io.github.kalpak44.workspacedai.api.backup;

import java.time.Instant;
import java.util.List;

/**
 * What a workspace is besides its home, stored beside the archive: the settings kept in the
 * database, and the names of what the user will want to know is in it. Secret values are left out
 * on purpose; only their names are kept, so the user knows which to add again.
 *
 * @param sessions the index that makes each restored conversation findable
 */
record BackupManifest(
    String modelProvider,
    String model,
    boolean sessionTtlEnabled,
    int sessionTtlHours,
    List<String> secrets,
    List<String> variables,
    List<String> instructions,
    List<String> tasks,
    List<Session> sessions) {

  record Session(String path, String harnessSessionId, String title, Instant createdAt) {}
}
