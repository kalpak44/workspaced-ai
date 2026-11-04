package io.github.kalpak44.workspacedai.api.harness;

import java.time.Instant;

/** A session as OpenCode V2 reports it for reconciliation with the application's index. */
public record HarnessSession(
    String id, String title, String directory, Instant createdAt, Instant updatedAt) {}
