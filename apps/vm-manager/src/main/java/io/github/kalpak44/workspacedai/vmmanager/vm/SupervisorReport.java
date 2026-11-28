package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Instant;

/** Untrusted agent input. Only bounded enum-like values are allowed into fleet logs. */
public record SupervisorReport(
    String installed, String desired, String state, Instant time, String failure) {
  public boolean safe() {
    return version(installed)
        && (desired == null || desired.isEmpty() || version(desired))
        && state != null
        && java.util.Set.of("paused", "current", "failed", "staged", "checking", "rolled_back")
            .contains(state)
        && (failure == null
            || java.util.Set.of(
                    "unsupported architecture",
                    "invalid release pin",
                    "release URL must use HTTPS",
                    "launcher unavailable",
                    "cannot stage update request",
                    "invalid release request",
                    "release download failed",
                    "release download rejected",
                    "cannot create staged binary",
                    "release download incomplete",
                    "release checksum mismatch",
                    "cannot chmod release",
                    "cannot install release",
                    "candidate checksum mismatch",
                    "candidate failed to start",
                    "candidate exited before ready",
                    "candidate health timeout",
                    "interrupted health check")
                .contains(failure));
  }

  private static boolean version(String value) {
    return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");
  }
}
