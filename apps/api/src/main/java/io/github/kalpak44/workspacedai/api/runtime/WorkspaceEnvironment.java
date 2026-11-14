package io.github.kalpak44.workspacedai.api.runtime;

import java.util.Map;
import java.util.TreeSet;

/**
 * What a workspace starts with, as environment variables.
 *
 * @param config values that are safe to read, such as the provider's base URL
 * @param secrets values that are not, such as the provider's API key
 */
public record WorkspaceEnvironment(Map<String, String> config, Map<String, String> secrets) {

  public WorkspaceEnvironment {
    config = Map.copyOf(config);
    secrets = Map.copyOf(secrets);
  }

  // Names only for the secrets, so a stray log line cannot carry a value.
  @Override
  public String toString() {
    return "WorkspaceEnvironment[config="
        + config
        + ", secrets="
        + new TreeSet<>(secrets.keySet())
        + "]";
  }
}
