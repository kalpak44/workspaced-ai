package io.github.kalpak44.workspacedai.api.session;

import java.util.List;

/** OpenCode is the source of truth for both available agents and the session's selection. */
public record AgentSelection(String current, List<Option> agents) {
  public record Option(String id, String name, String description, String mode) {}
}
