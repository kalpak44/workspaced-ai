package io.github.kalpak44.workspacedai.api.harness;

/** Content-free, ordered evidence from the pinned harness message history. */
public record UsageMessage(
    String id, String type, String provider, String model, Long completedAt, Tokens tokens) {
  public record Tokens(long input, long output, long reasoning, long cacheRead, long cacheWrite) {}
}
