package io.github.kalpak44.workspacedai.api.harness;

/** One event from the workspace-wide OpenCode stream, optionally belonging to a session. */
public record HarnessEventEnvelope(String sessionId, HarnessEvent event) {}
